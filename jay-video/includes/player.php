<?php
/**
 * Jay影视 - 播放源对接
 * 资源接口（如 https://api.yyzy-tv.vip/inc/apijson.php）→ 获取真实 m3u8 直链
 * 再拼接解析播放器外壳 https://svip.ffzyplay.com/?url={urlencode(直链)}
 */

define('PLAYER_SHELL', 'https://svip.ffzyplay.com/?url=');

/* ---------- 播放源管理 ---------- */
function play_sources_all(): array
{
    return db_q("SELECT * FROM play_sources ORDER BY is_default DESC, sort ASC, id ASC");
}

function play_source_default(): ?array
{
    $s = db_one("SELECT * FROM play_sources WHERE is_default=1 AND status=1 LIMIT 1");
    if (!$s) $s = db_one("SELECT * FROM play_sources WHERE status=1 ORDER BY id ASC LIMIT 1");
    return $s;
}

function play_source_by_id(int $id): ?array
{
    return db_one("SELECT * FROM play_sources WHERE id=? AND status=1", [$id]);
}

/** 生成解析播放器地址（仅接受 http(s) 直链，严禁传入资源接口地址） */
function build_player_url(string $realUrl): string
{
    $realUrl = trim($realUrl);
    if (strpos($realUrl, '//') === 0) $realUrl = 'https:' . $realUrl;
    return PLAYER_SHELL . rawurlencode($realUrl);
}

/* ---------- 资源接口解析 ---------- */

/** 从资源接口搜索影片（返回标准化列表，含缓存） */
function source_search(array $source, string $keyword): array
{
    $keyword = trim($keyword);
    if ($keyword === '' || empty($source['api_url'])) return [];

    $cacheKey = 'src:' . md5($source['id'] . '|' . $keyword);
    $cached = cache_get($cacheKey);
    if (is_array($cached)) return $cached;

    $kw = rawurlencode($keyword);
    $candidates = [
        $source['api_url'] . (strpos($source['api_url'], '?') === false ? '?' : '&') . 'ac=videolist&wd=' . $kw,
        $source['api_url'] . (strpos($source['api_url'], '?') === false ? '?' : '&') . 'wd=' . $kw,
        $source['api_url'] . (strpos($source['api_url'], '?') === false ? '?' : '&') . 'ac=detail&wd=' . $kw,
    ];

    foreach ($candidates as $url) {
        $raw = http_get($url, 10);
        if ($raw === '') continue;
        $data = json_decode($raw, true);
        if (!is_array($data)) continue;
        $list = $data['list'] ?? $data['data'] ?? $data['items'] ?? $data['vod_list'] ?? $data['results'] ?? null;
        if (!is_array($list)) continue;
        $out = [];
        foreach ($list as $item) {
            if (!is_array($item)) continue;
            $norm = source_norm_item($item);
            if ($norm['name'] !== '') $out[] = $norm;
        }
        if ($out) {
            cache_set($cacheKey, $out, 1800);
            return $out;
        }
    }
    return [];
}

/** 标准化资源条目 */
function source_norm_item(array $item): array
{
    $play = $item['vod_play_url'] ?? $item['play_url'] ?? $item['urls'] ?? $item['vod_url'] ?? $item['url'] ?? '';
    if (is_array($play)) {
        // [{name,url}] 或 ['第1集$http://...']
        $tmp = [];
        foreach ($play as $k => $v) {
            if (is_array($v)) $tmp[] = ($v['name'] ?? $k) . '$' . ($v['url'] ?? ($v['play_url'] ?? ''));
            else $tmp[] = (is_int($k) ? '' : $k) . '$' . $v;
        }
        $play = implode('#', $tmp);
    }
    return [
        'id'     => (string)($item['vod_id'] ?? $item['id'] ?? ''),
        'name'   => (string)($item['vod_name'] ?? $item['name'] ?? $item['title'] ?? ''),
        'pic'    => (string)($item['vod_pic'] ?? $item['pic'] ?? ''),
        'note'   => (string)($item['vod_remarks'] ?? $item['remarks'] ?? $item['note'] ?? ''),
        'year'   => (string)($item['vod_year'] ?? $item['year'] ?? ''),
        'area'   => (string)($item['vod_area'] ?? $item['area'] ?? ''),
        'play'   => (string)$play,
        'from'   => (string)($item['vod_play_from'] ?? $item['from'] ?? ''),
    ];
}

/** 按 id 拉取详情（补全播放地址） */
function source_fetch_detail(array $source, string $vodId): array
{
    if ($vodId === '') return [];
    $cacheKey = 'srcd:' . md5($source['id'] . '|' . $vodId);
    $cached = cache_get($cacheKey);
    if (is_array($cached)) return $cached;

    $sep = strpos($source['api_url'], '?') === false ? '?' : '&';
    foreach (['ac=videolist&ids=', 'ac=detail&ids='] as $q) {
        $raw = http_get($source['api_url'] . $sep . $q . rawurlencode($vodId), 10);
        if ($raw === '') continue;
        $data = json_decode($raw, true);
        if (!is_array($data)) continue;
        $list = $data['list'] ?? $data['data'] ?? $data['items'] ?? null;
        if (!is_array($list) || empty($list)) continue;
        $first = is_array($list[0]) ? source_norm_item($list[0]) : [];
        if ($first) {
            cache_set($cacheKey, $first, 1800);
            return $first;
        }
    }
    return [];
}

/**
 * 解析播放串为分组剧集
 * 格式：第01集$url1#第02集$url2$$$第01集$urlA#...
 */
function parse_play_groups(string $str): array
{
    $groups = [];
    $str = str_replace("\r\n", '#', $str);
    foreach (preg_split('/\$\$\$/', $str) as $part) {
        $eps = [];
        foreach (preg_split('/#|\$\$/', $part) as $seg) {
            $seg = trim($seg);
            if ($seg === '') continue;
            $name = '';
            $url = '';
            if (strpos($seg, '$') !== false) {
                [$name, $url] = array_pad(explode('$', $seg, 2), 2, '');
            } else {
                $url = $seg;
            }
            $name = trim($name);
            $url = trim($url);
            if ($url === '') continue;
            $eps[] = ['name' => $name, 'url' => $url];
        }
        if ($eps) $groups[] = $eps;
    }
    return $groups;
}

/** 选择最佳分组（优先 m3u8 数量多者） */
function pick_best_group(array $groups): array
{
    if (!$groups) return [];
    if (count($groups) === 1) return $groups[0];
    $best = null;
    $bestScore = -1;
    foreach ($groups as $g) {
        $m3u8 = 0;
        foreach ($g as $ep) {
            if (stripos($ep['url'], '.m3u8') !== false) $m3u8++;
        }
        $score = $m3u8 * 100 + count($g);
        if ($score > $bestScore) { $bestScore = $score; $best = $g; }
    }
    return $best ?: [];
}

/** 定位某一集 */
function pick_episode(array $eps, int $ep): array
{
    if (!$eps) return [];
    if ($ep <= 0) return $eps[0];
    if (isset($eps[$ep - 1])) return $eps[$ep - 1];
    // 按名称匹配 第N集 / 0N / N
    foreach ($eps as $item) {
        $n = $item['name'];
        if ($n !== '' && preg_match('/(?:第)?\s*0*' . (int)$ep . '\s*(?:集|话|期|集数)?$/u', trim($n))) return $item;
    }
    // 名称匹配失败：小于总集数按索引，否则取最后一集
    if ($ep <= count($eps)) return $eps[$ep - 1];
    return $eps[count($eps) - 1];
}

/** 校验直链是否安全可用（且不是资源接口本身） */
function valid_media_url(string $url, array $source): bool
{
    $url = trim($url);
    if ($url === '') return false;
    if (strpos($url, '//') === 0) return true;
    if (!preg_match('#^https?://#i', $url)) return false;
    $host = strtolower((string)parse_url($url, PHP_URL_HOST));
    $srcHost = strtolower((string)parse_url($source['api_url'], PHP_URL_HOST));
    if ($host !== '' && $host === $srcHost) return false; // 严禁把接口地址当直链
    return true;
}

/**
 * 解析媒体真实播放地址
 * @return array {ok, url, label, episodes, name, err}
 */
function resolve_play(array $source, string $title, int $episode): array
{
    $fail = function (string $err) {
        return ['ok' => false, 'url' => '', 'label' => '', 'episodes' => [], 'name' => '', 'err' => $err];
    };
    $title = trim($title);
    if ($title === '') return $fail('片名不能为空');
    if (!$source) return $fail('暂无可用播放源，请联系管理员');

    $list = source_search($source, $title);
    if (!$list) return $fail('播放源未收录《' . $title . '》');

    // 精确匹配 → 包含匹配 → 名称去掉年份匹配
    $item = null;
    foreach ($list as $it) {
        if ($it['name'] === $title) { $item = $it; break; }
    }
    if (!$item) {
        foreach ($list as $it) {
            if (mb_strpos($it['name'], $title) !== false) { $item = $it; break; }
        }
    }
    if (!$item) {
        $pureTitle = preg_replace('/\s*(国语|普通话|粤语|高清|完整|版)*$/u', '', $title);
        foreach ($list as $it) {
            if (mb_strpos($it['name'], $pureTitle) !== false) { $item = $it; break; }
        }
    }
    if (!$item) $item = $list[0];

    // 无播放串则拉详情
    if ($item['play'] === '' && $item['id'] !== '') {
        $detail = source_fetch_detail($source, $item['id']);
        if ($detail) $item = $detail;
    }
    if ($item['play'] === '') return $fail('播放源未返回播放地址');

    $groups = parse_play_groups($item['play']);
    $eps = pick_best_group($groups);
    if (!$eps) return $fail('播放地址解析失败');

    $chosen = pick_episode($eps, $episode);
    if (!valid_media_url($chosen['url'], $source)) return $fail('播放直链不可用');

    return [
        'ok'       => true,
        'url'      => $chosen['url'],
        'label'    => $chosen['name'] !== '' ? $chosen['name'] : ('第' . max($episode, 1) . '集'),
        'episodes' => $eps,
        'name'     => $item['name'],
        'err'      => '',
    ];
}

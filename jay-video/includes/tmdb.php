<?php
/**
 * Jay影视 - TMDB API 封装（走 api.tmdb.org / images.tmdb.org 代理，MySQL 缓存）
 */

define('TMDB_API_BASE', 'https://api.tmdb.org/3');
define('TMDB_IMG_BASE', 'https://images.tmdb.org/t/p');

function tmdb_key(): string
{
    return trim(setting('tmdb_api_key', ''));
}

/** 缓存读写 */
function cache_get(string $key)
{
    $row = db_one("SELECT cache_value FROM cache WHERE cache_key=? AND expires_at>NOW()", [$key]);
    return $row ? json_decode($row['cache_value'], true) : null;
}

function cache_set(string $key, $value, int $ttl = 1800): void
{
    try {
        db_x("INSERT INTO cache (cache_key,cache_value,expires_at) VALUES (?,?,DATE_ADD(NOW(),INTERVAL " . (int)$ttl . " SECOND))
              ON DUPLICATE KEY UPDATE cache_value=VALUES(cache_value),expires_at=VALUES(expires_at)",
            [$key, json_encode($value, JSON_UNESCAPED_UNICODE)]);
    } catch (Throwable $t) { /* 缓存失败不阻塞 */ }
}

/** HTTP GET（curl 优先） */
function http_get(string $url, int $timeout = 12): string
{
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS      => 3,
            CURLOPT_TIMEOUT        => $timeout,
            CURLOPT_CONNECTTIMEOUT => 8,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_SSL_VERIFYHOST => 0,
            CURLOPT_USERAGENT      => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) JayVideo/1.0',
        ]);
        $body = curl_exec($ch);
        $err = curl_error($ch);
        curl_close($ch);
        if ($body !== false) return (string)$body;
        return '';
    }
    $ctx = stream_context_create(['http' => ['timeout' => $timeout, 'header' => 'User-Agent: JayVideo/1.0']]);
    $body = @file_get_contents($url, false, $ctx);
    return $body === false ? '' : (string)$body;
}

/**
 * TMDB GET 请求（带 MySQL 缓存）
 * @return array|null 失败返回 null
 */
function tmdb_get(string $path, array $params = [], int $ttl = 1800)
{
    $key = trim(setting('tmdb_api_key', ''));
    if ($key === '') return null;

    $params = array_merge([
        'api_key'       => $key,
        'language'      => 'zh-CN',
        'include_adult' => 'false',
    ], $params);
    unset($params['api_key__']);

    $cacheKey = 'tmdb:' . md5($path . '|' . json_encode($params));
    $cached = cache_get($cacheKey);
    if ($cached !== null) return $cached;

    $url = TMDB_API_BASE . $path . '?' . http_build_query($params);
    $raw = http_get($url);
    if ($raw === '') return null;
    $data = json_decode($raw, true);
    if (!is_array($data)) return null;
    // TMDB 错误结构 {"success":false,"status_message":...}
    if (isset($data['success']) && $data['success'] === false) return null;

    cache_set($cacheKey, $data, $ttl);
    return $data;
}

/** TMDB 图片地址 */
function tmdb_img(?string $path, string $size = 'w500'): string
{
    if (!$path) return '';
    return TMDB_IMG_BASE . '/' . $size . $path;
}

/** 是否海外影视（非国产 => 展示音轨切换） */
function tmdb_is_foreign(array $media): bool
{
    $lang = $media['original_language'] ?? '';
    if ($lang !== '' && $lang !== 'zh') return true;
    $countries = $media['production_countries'] ?? $media['origin_country'] ?? [];
    if (empty($countries)) return $lang !== 'zh';
    $codes = [];
    foreach ($countries as $c) {
        $codes[] = is_array($c) ? ($c['iso_3166_1'] ?? '') : $c;
    }
    $isCN = in_array('CN', $codes, true) || in_array('HK', $codes, true) || in_array('TW', $codes, true);
    return !$isCN;
}

/** 标准化 TMDB 条目（trending/multi 混合 movie/tv） */
function tmdb_norm(array $item): array
{
    $isTV = ($item['media_type'] ?? '') === 'tv' || isset($item['name']) && !isset($item['title']) && isset($item['first_air_date']);
    $type = $item['media_type'] ?? ($isTV ? 'tv' : 'movie');
    if ($type !== 'tv' && $type !== 'movie') $type = isset($item['title']) ? 'movie' : 'tv';
    return [
        'id'       => (int)($item['id'] ?? 0),
        'type'     => $type,
        'title'    => $item['title'] ?? $item['name'] ?? '',
        'orig'     => $item['original_title'] ?? $item['original_name'] ?? '',
        'poster'   => tmdb_img($item['poster_path'] ?? null, 'w500'),
        'backdrop' => tmdb_img($item['backdrop_path'] ?? null, 'w1280'),
        'score'    => round((float)($item['vote_average'] ?? 0), 1),
        'year'     => mb_substr($item['release_date'] ?? $item['first_air_date'] ?? '', 0, 4),
        'remark'   => $item['media_type'] ?? '',
    ];
}

/** 首页轮播（热门，含背景图） */
function tmdb_hero_list(): array
{
    $data = tmdb_get('/trending/all/week', [], 3600);
    $list = [];
    if (!empty($data['results'])) {
        foreach ($data['results'] as $item) {
            $n = tmdb_norm($item);
            if ($n['id'] && $n['backdrop'] && $n['title']) $list[] = $n;
            if (count($list) >= 5) break;
        }
    }
    return $list;
}

/** 横向区块列表 */
function tmdb_row(string $path, array $params = []): array
{
    $data = tmdb_get($path, $params, 3600);
    $list = [];
    if (!empty($data['results'])) {
        foreach ($data['results'] as $item) {
            if (($item['media_type'] ?? '') === 'person') continue;
            $n = tmdb_norm($item);
            if ($n['id'] && $n['poster']) $list[] = $n;
            if (count($list) >= 12) break;
        }
    }
    return $list;
}

/** 媒体卡片 HTML */
function media_card_html(array $m, int $i = 0): string
{
    $title = $m['title'] ?: ($m['orig'] ?: '未知影片');
    $href = u('detail.php?type=' . $m['type'] . '&id=' . $m['id']);
    $remark = $m['year'] ?: (($m['type'] === 'tv') ? '剧集' : '电影');
    $score = $m['score'] > 0 ? '<span class="poster-score"><i class="ic ic-star"></i>' . $m['score'] . '</span>' : '';
    $ani = 'style="animation-delay:' . min($i * 40, 400) . 'ms"';
    return '<a class="media-card" href="' . e($href) . '" ' . $ani . '>
        <div class="poster">
            <img class="poster-img" data-fade src="' . e($m['poster']) . '" alt="' . e($title) . '" loading="lazy">
            <span class="poster-remark">' . e($remark) . '</span>
            ' . $score . '
            <span class="poster-play"><i class="ic ic-play"></i></span>
        </div>
        <h4>' . e($title) . '</h4>
        <div class="sub">' . e($m['year'] ? $m['year'] : '') . '</div>
    </a>';
}

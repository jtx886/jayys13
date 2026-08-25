<?php
/** Jay影视 - 影视详情页（季切换 / 单集封面 / 音轨切换 / 收藏） */
require_once __DIR__ . '/includes/bootstrap.php';

$type = ($_GET['type'] ?? 'movie') === 'tv' ? 'tv' : 'movie';
$id = (int)($_GET['id'] ?? 0);
$U = current_user();

if ($id <= 0) redirect(u('index.php'));

/* 收藏切换 */
if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $act = $_POST['act'] ?? '';
    if ($act === 'fav' && $U) {
        if (fav_exists((int)$U['id'], $id, $type)) {
            db_x("DELETE FROM favorites WHERE user_id=? AND tmdb_id=? AND media_type=?", [(int)$U['id'], $id, $type]);
            flash_set('已取消收藏', 'info');
        } else {
            $media0 = tmdb_get("/{$type}/{$id}", [], 7200);
            $t = $media0['title'] ?? $media0['name'] ?? '';
            db_x("INSERT IGNORE INTO favorites (user_id,tmdb_id,media_type,title,poster,created_at) VALUES (?,?,?,?,?,NOW())", [
                (int)$U['id'], $id, $type, $t, tmdb_img($media0['poster_path'] ?? null, 'w300'),
            ]);
            flash_set('已加入收藏', 'success');
        }
    }
    redirect(u('detail.php?type=' . $type . '&id=' . $id . (!empty($_GET['season']) ? '&season=' . (int)$_GET['season'] : '') . (!empty($_GET['audio']) ? '&audio=' . $_GET['audio'] : '')));
}

$media = tmdb_get("/{$type}/{$id}", ['append_to_response' => 'credits'], 3600);
if (!$media) {
    $PAGE_TITLE = '未找到影片 - ' . site_name();
    require_once __DIR__ . '/includes/header.php';
    echo '<div class="container"><div class="empty" style="margin-top:80px"><div class="empty-icon"></div><p>无法获取影视信息（可能未配置 TMDB API Key 或资源不存在）</p><a class="btn btn-primary" href="' . u('index.php') . '">回到首页</a></div></div>';
    require_once __DIR__ . '/includes/footer.php';
    exit;
}

$title = $type === 'movie' ? ($media['title'] ?? '') : ($media['name'] ?? '');
$origTitle = $type === 'movie' ? ($media['original_title'] ?? '') : ($media['original_name'] ?? '');
$overview = $media['overview'] ?? '暂无简介';
$poster = tmdb_img($media['poster_path'] ?? null, 'w500');
$backdrop = tmdb_img($media['backdrop_path'] ?? null, 'w1280');
$score = round((float)($media['vote_average'] ?? 0), 1);
$date = $type === 'movie' ? ($media['release_date'] ?? '') : ($media['first_air_date'] ?? '');
$year = mb_substr($date, 0, 4);
$genres = array_column($media['genres'] ?? [], 'name');
$isForeign = tmdb_is_foreign($media);
$runtime = $type === 'movie'
    ? (($media['runtime'] ?? 0) ? $media['runtime'] . ' 分钟' : '')
    : ((int)($media['number_of_seasons'] ?? 0) . ' 季 / ' . (int)($media['number_of_episodes'] ?? 0) . ' 集');

/* 音轨选择（海外影视：普通话/原版；国产隐藏自动匹配） */
$audio = $_GET['audio'] ?? '';
if ($isForeign && !in_array($audio, ['zh', 'orig'], true)) $audio = 'orig';
$audioQ = $isForeign ? '&audio=' . $audio : '';

/* 季数据（剧集） */
$seasons = [];
$seasonData = null;
$seasonCredits = null;
$watchedEps = [];
if ($type === 'tv') {
    foreach (($media['seasons'] ?? []) as $s) {
        if ((int)($s['episode_count'] ?? 0) === 0 && (int)($s['season_number'] ?? 0) > 0) continue;
        $seasons[] = ['num' => (int)($s['season_number'] ?? 1), 'name' => $s['name'] ?? ('第' . $s['season_number'] . '季'), 'count' => (int)($s['episode_count'] ?? 0)];
    }
    if (!$seasons) $seasons[] = ['num' => 1, 'name' => '第 1 季', 'count' => 0];

    $season = (int)($_GET['season'] ?? 1);
    $validNums = array_column($seasons, 'num');
    if (!in_array($season, $validNums, true)) $season = $validNums[0] ?? 1;

    $seasonData = tmdb_get("/tv/{$id}/season/{$season}", [], 3600);
    if (!$seasonData || empty($seasonData['episodes'])) {
        // 当前季无数据时回退到第一个有数据的季
        foreach ($validNums as $vn) {
            if ($vn === $season) continue;
            $tmp = tmdb_get("/tv/{$id}/season/{$vn}", [], 3600);
            if ($tmp && !empty($tmp['episodes'])) { $seasonData = $tmp; $season = $vn; break; }
        }
    }
    $seasonCredits = tmdb_get("/tv/{$id}/season/{$season}/credits", [], 3600);

    /* 已观看集数标记 */
    if ($U) {
        foreach (db_q("SELECT episode FROM watch_history WHERE user_id=? AND tmdb_id=? AND media_type='tv' AND season=?", [(int)$U['id'], $id, $season]) as $r) {
            $watchedEps[(int)$r['episode']] = (int)$r['position_seconds'];
        }
    }
    $curSeason = $season;
} else {
    $curSeason = 1;
}

/* 演员表：季演员优先，剧集/电影 credits 兜底 */
$cast = [];
$candidates = $type === 'tv' ? (($seasonCredits['cast'] ?? []) ?: ($media['credits']['cast'] ?? [])) : ($media['credits']['cast'] ?? []);
foreach (array_slice($candidates, 0, 12) as $c) {
    if (!empty($c['profile_path'])) $cast[] = ['name' => $c['name'], 'role' => $c['character'] ?? '', 'img' => tmdb_img($c['profile_path'], 'w185')];
}

/* 收藏 / 继续观看 */
$isFav = $U ? fav_exists((int)$U['id'], $id, $type) : false;
$lastHist = $U ? history_latest((int)$U['id'], $id, $type) : null;

/* 推荐 */
$recPath = $type === 'movie' ? "/movie/{$id}/recommendations" : "/tv/{$id}/recommendations";
$recs = tmdb_row($recPath);
$recs = array_values(array_filter($recs, fn($m) => $m['id'] !== $id));

$playUrl = u('play.php?type=' . $type . '&id=' . $id . '&season=' . $curSeason . '&episode=1' . $audioQ);
$PAGE_TITLE = $title . ' - ' . site_name();
require_once __DIR__ . '/includes/header.php';
?>

<div class="container">
  <div class="detail-hero">
    <div class="detail-backdrop" style="background-image:url('<?= e($backdrop ?: $poster) ?>')"></div>
    <div class="detail-body">
      <img class="detail-poster" src="<?= e($poster) ?>" alt="<?= e($title) ?>" data-fade>
      <div class="detail-info">
        <h1 class="detail-title"><?= e($title) ?></h1>
        <?php if ($origTitle && $origTitle !== $title): ?><div class="detail-orig"><?= e($origTitle) ?></div><?php endif; ?>
        <div class="detail-meta">
          <?php if ($score): ?><span class="score">评分 <?= $score ?></span><?php endif; ?>
          <span><?= e($year) ?></span>
          <span><?= $type === 'movie' ? '电影' : '剧集' ?></span>
          <?php if ($runtime): ?><span><?= e($runtime) ?></span><?php endif; ?>
          <?php foreach (array_slice($genres, 0, 4) as $g): ?><span class="tag"><?= e($g) ?></span><?php endforeach; ?>
        </div>
        <p class="detail-overview"><?= e($overview) ?></p>
        <div class="detail-btns">
          <?php if ($lastHist): ?>
          <a class="btn btn-green" href="<?= u('play.php?type=' . $type . '&id=' . $id . '&season=' . (int)$lastHist['season'] . '&episode=' . (int)$lastHist['episode'] . $audioQ) ?>">
            继续观看 <?= $type === 'tv' ? '第' . (int)$lastHist['episode'] . '集' : '' ?>
          </a>
          <?php else: ?>
          <a class="btn btn-primary btn-lg" href="<?= $playUrl ?>">立即播放</a>
          <?php endif; ?>
          <form method="post" action="<?= u('detail.php?type=' . $type . '&id=' . $id . ($type === 'tv' ? '&season=' . $curSeason : '') . $audioQ) ?>" style="display:inline">
            <?= csrf_field() ?>
            <input type="hidden" name="act" value="fav">
            <?php if ($U): ?>
            <button class="btn <?= $isFav ? 'btn-primary' : 'btn-ghost' ?> btn-lg" type="submit">
              <?= $isFav ? '已收藏' : '收藏' ?>
            </button>
            <?php else: ?>
            <a class="btn btn-ghost btn-lg" href="<?= u('login.php?from=play') ?>">收藏</a>
            <?php endif; ?>
          </form>
        </div>
      </div>
    </div>
  </div>

  <?php if ($isForeign): ?>
  <div style="margin-top:18px;display:flex;align-items:center;gap:14px;flex-wrap:wrap">
    <span style="font-size:13px;color:var(--text-3)">配音版本：</span>
    <div class="audio-switch">
      <a class="<?= $audio === 'orig' ? 'active' : '' ?>" href="<?= u('detail.php?type=' . $type . '&id=' . $id . ($type === 'tv' ? '&season=' . $curSeason : '') . '&audio=orig') ?>">原版</a>
      <a class="<?= $audio === 'zh' ? 'active' : '' ?>" href="<?= u('detail.php?type=' . $type . '&id=' . $id . ($type === 'tv' ? '&season=' . $curSeason : '') . '&audio=zh') ?>">普通话</a>
    </div>
    <span style="font-size:12px;color:var(--text-3)">海外影视支持配音切换，播放时自动匹配对应资源</span>
  </div>
  <?php endif; ?>

  <?php if ($type === 'tv' && $seasonData): ?>
  <section class="section">
    <div class="section-head">
      <h2 class="section-title">分集剧情</h2>
      <span style="font-size:12.5px;color:var(--text-3)"><?= count($seasonData['episodes'] ?? []) ?> 集</span>
    </div>

    <div class="season-bar">
      <?php foreach ($seasons as $s): ?>
      <a class="season-pill <?= (int)$s['num'] === (int)$curSeason ? 'active' : '' ?>"
         href="<?= u('detail.php?type=tv&id=' . $id . '&season=' . (int)$s['num'] . $audioQ) ?>">
        <?= e($s['name']) ?><?php if ($s['count']): ?> · <?= (int)$s['count'] ?>集<?php endif; ?>
      </a>
      <?php endforeach; ?>
    </div>

    <div class="season-info">
      <div class="si-score">
        <b>评分 <?= round((float)($seasonData['vote_average'] ?? 0), 1) ?></b>
        <span>本季评分</span>
      </div>
      <div class="si-main">
        <h3><?= e($seasonData['name'] ?: ('第 ' . $curSeason . ' 季')) ?></h3>
        <div class="si-meta">
          <?= e(mb_substr((string)($seasonData['air_date'] ?? ''), 0, 4) ?: '年份未知') ?> 年首播
          <?php if (!empty($seasonData['episodes'])): ?> · 共 <?= count($seasonData['episodes']) ?> 集<?php endif; ?>
        </div>
        <p><?= e($seasonData['overview'] ?: '本季暂无简介，可切换其他季查看。') ?></p>
      </div>
    </div>

    <div class="episode-grid">
      <?php $epi = 0; foreach (($seasonData['episodes'] ?? []) as $ep): $epi++; $en = (int)($ep['episode_number'] ?? $epi); ?>
      <a class="ep-card <?= isset($watchedEps[$en]) ? 'watched' : '' ?>"
         href="<?= u('play.php?type=tv&id=' . $id . '&season=' . $curSeason . '&episode=' . $en . $audioQ) ?>">
        <div class="ep-thumb">
          <img src="<?= e(tmdb_img($ep['still_path'] ?? null, 'w300') ?: $backdrop) ?>" alt="<?= e($ep['name'] ?? ('第' . $en . '集')) ?>" data-fade loading="lazy">
          <span class="ep-num">第 <?= $en ?> 集</span>
          <?php if (!empty($ep['vote_average'])): ?><span class="ep-score">评分 <?= round((float)$ep['vote_average'], 1) ?></span><?php endif; ?>
          <span class="ep-play">播放</span>
        </div>
        <div class="ep-body">
          <h5><?= e($ep['name'] ?? ('第' . $en . '集')) ?></h5>
          <p><?= e($ep['overview'] ?? '暂无单集简介') ?></p>
          <span class="ep-date"><?= e($ep['air_date'] ?? '待定') ?></span>
        </div>
      </a>
      <?php endforeach; ?>
    </div>
  </section>
  <?php endif; ?>

  <?php if ($cast): ?>
  <section class="section">
    <div class="section-head"><h2 class="section-title">演职人员</h2></div>
    <div class="cast-row">
      <?php foreach ($cast as $c): ?>
      <div class="cast-card">
        <img class="cast-img" src="<?= e($c['img']) ?>" alt="<?= e($c['name']) ?>" data-fade loading="lazy">
        <div class="cast-name"><?= e($c['name']) ?></div>
        <div class="cast-role">饰 <?= e($c['role'] ?: '-') ?></div>
      </div>
      <?php endforeach; ?>
    </div>
  </section>
  <?php endif; ?>

  <?php if ($recs): ?>
  <section class="section">
    <div class="section-head"><h2 class="section-title">相关推荐</h2></div>
    <div class="media-grid">
      <?php foreach (array_slice($recs, 0, 12) as $i => $m) echo media_card_html($m, $i); ?>
    </div>
  </section>
  <?php endif; ?>
</div>
<?php require_once __DIR__ . '/includes/footer.php'; ?>

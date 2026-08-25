<?php
/** Jay影视 - 播放页（资源接口取真实直链 → urlencode → 拼接解析播放器 → iframe） */
require_once __DIR__ . '/includes/bootstrap.php';

$type = ($_GET['type'] ?? 'movie') === 'tv' ? 'tv' : 'movie';
$id = (int)($_GET['id'] ?? 0);
$season = max(1, (int)($_GET['season'] ?? 1));
$episode = max(1, (int)($_GET['episode'] ?? 1));
$audio = ($_GET['audio'] ?? '') === 'zh' ? 'zh' : '';
$sourceId = (int)($_GET['source'] ?? 0);

/* ---------- 权限：未登录禁止播放 ---------- */
if (!is_logged_in()) {
    $PAGE_TITLE = '登录后观看 - ' . site_name();
    require_once __DIR__ . '/includes/header.php';
    ?>
    <div class="login-wall">
      <div class="login-wall-card">
        <div class="login-wall-icon"><i class="ic ic-lock"></i></div>
        <h2>需要登录才可以观看哦，如没有账号请注册！</h2>
        <p>登录后即可播放《<?= e($_GET['t'] ?? '精彩影片') ?>》并同步观看进度</p>
        <div style="display:flex;gap:12px;justify-content:center;flex-wrap:wrap">
          <a class="btn btn-primary btn-lg" href="<?= u('login.php?from=play') ?>"><i class="ic ic-user"></i>去登录</a>
          <a class="btn btn-ghost btn-lg" href="<?= u('register.php') ?>"><i class="ic ic-plus"></i>免费注册</a>
        </div>
        <p style="margin-top:18px;font-size:12px;color:var(--text-3)"><span id="lw-count">3</span> 秒后自动跳转登录页…</p>
      </div>
    </div>
    <script>
      var n = 3;
      setInterval(function () {
        n--;
        var el = document.getElementById('lw-count');
        if (el) el.textContent = n > 0 ? n : 0;
        if (n <= 0) location.href = '<?= u('login.php?from=play') ?>';
      }, 1000);
    </script>
    <?php
    require_once __DIR__ . '/includes/footer.php';
    exit;
}

$U = current_user();

/* ---------- 媒体信息 ---------- */
$media = tmdb_get("/{$type}/{$id}", [], 7200);
$title = $type === 'tv' ? ($media['name'] ?? '') : ($media['title'] ?? '');
if ($title === '') $title = '未知影片';
$poster = tmdb_img($media['poster_path'] ?? null, 'w300');
$isForeign = $media ? tmdb_is_foreign($media) : false;

/* ---------- 播放源 ---------- */
$sources = play_sources_all();
$source = $sourceId > 0 ? play_source_by_id($sourceId) : play_source_default();

/* ---------- 解析直链 ---------- */
$keyword = $title;
if ($isForeign && $audio === 'zh') $keyword = $title . ' 国语';

$res = ['ok' => false, 'err' => ''];
$episodes = [];
if ($source) {
    $res = resolve_play($source, $keyword, $type === 'tv' ? $episode : 0);
    if ($res['ok']) $episodes = $res['episodes'];
}

$playerUrl = $res['ok'] ? build_player_url($res['url']) : '';
$epName = $res['ok'] ? ($res['label'] ?: ('第' . $episode . '集')) : '';
$lastHist = history_latest((int)$U['id'], $id, $type);

$playBase = 'play.php?type=' . $type . '&id=' . $id
    . ($type === 'tv' ? '&season=' . $season : '')
    . ($audio ? '&audio=' . $audio : '');
$PAGE_TITLE = $title . ' 在线播放 - ' . site_name();
require_once __DIR__ . '/includes/header.php';
?>
<div class="container player-shell">
  <div class="player-top">
    <div>
      <div class="player-title">
        <a class="icon-btn" href="<?= u('detail.php?type=' . $type . '&id=' . $id) ?>" title="返回详情"><i class="ic ic-arrow-l"></i></a>
        <a href="<?= u('detail.php?type=' . $type . '&id=' . $id) ?>" style="font-size:20px;font-weight:800"><?= e($title) ?></a>
        <?php if ($type === 'tv'): ?><span class="tag">S<?= $season ?>E<?= $episode ?></span><?php endif; ?>
        <?php if ($isForeign && $audio === 'zh'): ?><span class="tag" style="color:var(--primary-2);border-color:var(--primary-30)">普通话配音</span>
        <?php elseif ($isForeign): ?><span class="tag">原声</span><?php endif; ?>
      </div>
      <p class="player-sub"><?= $source ? '播放源：' . e($source['name']) : '暂无可用播放源' ?><?= $res['ok'] ? ' · 解析成功' : '' ?></p>
    </div>
    <?php if ($res['ok']): ?>
    <div style="display:flex;gap:10px;align-items:center">
      <form method="post" action="<?= u('detail.php?type=' . $type . '&id=' . $id) ?>" style="display:inline">
        <?= csrf_field() ?><input type="hidden" name="act" value="fav">
        <?php if (fav_exists((int)$U['id'], $id, $type)): ?>
        <button class="btn btn-primary btn-sm" type="submit"><i class="ic ic-heart on"></i>已收藏</button>
        <?php else: ?>
        <button class="btn btn-ghost btn-sm" type="submit"><i class="ic ic-heart"></i>收藏</button>
        <?php endif; ?>
      </form>
    </div>
    <?php endif; ?>
  </div>

  <?php if ($res['ok']): ?>
  <div class="player-box">
    <div class="player-loading" id="player-loading"><i class="ic ic-spin"></i><span>播放器加载中，请稍候…</span></div>
    <iframe id="jay-player" src="<?= e($playerUrl) ?>" allowfullscreen allow="autoplay; fullscreen; encrypted-media" referrerpolicy="no-referrer" scrolling="no"></iframe>
  </div>
  <script>setTimeout(function(){var l=document.getElementById('player-loading');if(l)l.style.display='none';},6000);</script>

  <div class="player-meta-bar">
    <span class="pmb-item"><i class="ic ic-film"></i><?= $type === 'movie' ? '正片' : e($epName) ?></span>
    <span class="pmb-item"><i class="ic ic-clock"></i>观看时长：<b id="wk-time" style="color:var(--green)">0秒</b>（自动记录）</span>
    <?php if ($lastHist): ?>
    <span class="pmb-item"><i class="ic ic-eye"></i>上次观看：<?= e($lastHist['title']) ?><?= $type === 'tv' ? ' 第' . (int)$lastHist['episode'] . '集' : '' ?> · <?= e(format_seconds((int)$lastHist['position_seconds'])) ?></span>
    <?php endif; ?>
  </div>

  <?php if ($type === 'tv' && count($episodes) > 1): ?>
  <div class="ep-panel">
    <div class="section-head"><h2 class="section-title">选集</h2><span style="font-size:12.5px;color:var(--text-3)">共 <?= count($episodes) ?> 集</span></div>
    <div class="ep-chips">
      <?php
      $watched = [];
      foreach (db_q("SELECT episode,position_seconds FROM watch_history WHERE user_id=? AND tmdb_id=? AND media_type='tv' AND season=?", [(int)$U['id'], $id, $season]) as $r) {
          $watched[(int)$r['episode']] = (int)$r['position_seconds'];
      }
      foreach ($episodes as $idx => $ep):
          $epNo = $idx + 1;
          // 通过名称提取真实集数，避免源站集数错位
          if (preg_match('/(\d+)/', $ep['name'] ?? '', $mm)) $epNo = (int)$mm[1];
          $href = u($playBase . '&episode=' . $epNo . ($sourceId ? '&source=' . $sourceId : ''));
      ?>
      <a class="chip <?= $epNo === $episode ? 'active' : '' ?> <?= isset($watched[$epNo]) ? 'watched-chip' : '' ?>" href="<?= $href ?>">
        <?= e($ep['name'] !== '' ? $ep['name'] : ('第' . $epNo . '集')) ?>
      </a>
      <?php endforeach; ?>
    </div>
  </div>
  <?php endif; ?>

  <?php if (count($sources) > 1): ?>
  <div class="ep-panel">
    <div class="section-head"><h2 class="section-title">播放线路</h2><span style="font-size:12.5px;color:var(--text-3)">当前线路异常时可切换</span></div>
    <div class="source-pills">
      <?php foreach ($sources as $s): ?>
      <a class="source-pill <?= $source && (int)$s['id'] === (int)$source['id'] ? 'active' : '' ?>"
         href="<?= u($playBase . '&episode=' . $episode . '&source=' . (int)$s['id']) ?>"><?= e($s['name']) ?><?= (int)$s['is_default'] === 1 ? '（默认）' : '' ?></a>
      <?php endforeach; ?>
    </div>
  </div>
  <?php endif; ?>

  <script>
  (function () {
    var seconds = 0, timer = null;
    var fmt = function (s) {
      if (s < 60) return s + '秒';
      var m = Math.floor(s / 60);
      if (m < 60) return m + '分' + (s % 60 > 0 ? (s % 60) + '秒' : '');
      return Math.floor(m / 60) + '小时' + (m % 60) + '分';
    };
    var el = document.getElementById('wk-time');
    timer = setInterval(function () {
      if (document.visibilityState === 'visible') {
        seconds++;
        if (el) el.textContent = fmt(seconds);
        if (seconds % 15 === 0) send();
      }
    }, 1000);
    function payload() {
      return 'action=progress&type=<?= $type ?>&id=<?= $id ?>&season=<?= $season ?>&episode=<?= $episode ?>'
        + '&seconds=' + seconds + '&title=' + encodeURIComponent('<?= e($title) ?>')
        + '&poster=' + encodeURIComponent('<?= e($poster) ?>')
        + '&epname=' + encodeURIComponent('<?= e($epName) ?>')
        + '&csrf=' + encodeURIComponent(window.JAY_CSRF || '');
    }
    function send() {
      if (seconds <= 0) return;
      fetch('api.php', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: payload()}).catch(function(){});
    }
    window.addEventListener('beforeunload', function () {
      if (seconds > 0 && navigator.sendBeacon) {
        navigator.sendBeacon('api.php', new Blob([payload()], {type: 'application/x-www-form-urlencoded'}));
      }
    });
  })();
  </script>

  <?php else: ?>
  <div class="play-err">
    <i class="ic ic-info"></i>
    <h3>抱歉，本片暂时无法解析播放地址</h3>
    <p><?= e($res['err'] ?: '播放源暂未收录本片，可尝试切换其他线路') ?></p>
    <div style="display:flex;gap:12px;justify-content:center;flex-wrap:wrap">
      <?php foreach ($sources as $s): if ($source && (int)$s['id'] === (int)$source['id']) continue; ?>
      <a class="btn btn-ghost" href="<?= u($playBase . '&episode=' . $episode . '&source=' . (int)$s['id']) ?>"><i class="ic ic-db"></i>切换到 <?= e($s['name']) ?></a>
      <?php endforeach; ?>
      <a class="btn btn-primary" href="<?= u('detail.php?type=' . $type . '&id=' . $id) ?>"><i class="ic ic-arrow-l"></i>返回详情</a>
    </div>
  </div>
  <?php endif; ?>
</div>
<?php require_once __DIR__ . '/includes/footer.php'; ?>

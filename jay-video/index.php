<?php
/** Jay影视 - 首页（公告弹窗仅在本页显示） */
require_once __DIR__ . '/includes/bootstrap.php';

$hero   = tmdb_hero_list();
$movies = tmdb_row('/movie/popular');
$tvs    = tmdb_row('/tv/popular');
$shows  = tmdb_row('/discover/tv', ['with_genres' => '10764,10767', 'sort_by' => 'popularity.desc']);
$animes = tmdb_row('/discover/tv', ['with_genres' => '16', 'sort_by' => 'popularity.desc']);
$hasData = !empty($hero) || !empty($movies);

/* 首页公告 */
$notice = db_one("SELECT * FROM notices WHERE is_active=1 ORDER BY id DESC LIMIT 1");
$showNotice = false;
if ($notice) {
    $acked = $_COOKIE['jay_notice_ack'] ?? '';
    if ($acked !== (string)$notice['id']) $showNotice = true;
}

$PAGE_TITLE = site_name() . ' - 暗夜观影，光影随行';
require_once __DIR__ . '/includes/header.php';
?>

<?php if (!$hasData): ?>
<div class="container">
  <div class="empty" style="margin-top:60px">
    <div class="empty-icon"><i class="ic ic-film"></i></div>
    <p style="font-size:15px;color:var(--text-2)">影视数据加载中或未配置 TMDB API Key</p>
    <p style="margin-top:6px">管理员请登录后进入「管理后台 → 网站设置」填写 TMDB API Key</p>
  </div>
</div>
<?php endif; ?>

<?php if ($hero): ?>
<div class="container">
  <div class="hero" id="hero">
    <?php foreach ($hero as $i => $m): ?>
    <a class="hero-slide <?= $i === 0 ? 'active' : '' ?>" href="<?= u('detail.php?type=' . $m['type'] . '&id=' . $m['id']) ?>">
      <div class="hero-bg" style="background-image:url('<?= e($m['backdrop']) ?>')"></div>
      <div class="hero-mask"></div>
      <div class="hero-body">
        <span class="hero-tag"><i class="ic ic-fire" style="width:14px;height:14px"></i>本周热门 · <?= $m['type'] === 'tv' ? '剧集' : '电影' ?></span>
        <h1 class="hero-title"><?= e($m['title']) ?></h1>
        <div class="hero-meta">
          <?php if ($m['score']): ?><span class="score"><i class="ic ic-star"></i><?= $m['score'] ?></span><span class="sep"></span><?php endif; ?>
          <span><?= e($m['year']) ?></span>
          <span class="sep"></span>
          <span><?= $m['type'] === 'tv' ? '剧集' : '电影' ?></span>
        </div>
        <p class="hero-desc">高清资源在线播放，登录后即可观看 <?= e($m['title']) ?>，支持收藏与观看记录同步。</p>
        <div class="hero-btns">
          <span class="btn btn-primary btn-lg"><i class="ic ic-play"></i>立即播放</span>
          <span class="btn btn-ghost btn-lg"><i class="ic ic-info"></i>查看详情</span>
        </div>
      </div>
    </a>
    <?php endforeach; ?>
    <div class="hero-dots">
      <?php foreach ($hero as $i => $m): ?><button class="hero-dot <?= $i === 0 ? 'active' : '' ?>" aria-label="第<?= $i + 1 ?>张"></button><?php endforeach; ?>
    </div>
    <div class="hero-side">
      <button class="hero-arrow hero-prev"><i class="ic ic-arrow-l"></i></button>
      <button class="hero-arrow hero-next"><i class="ic ic-arrow-r"></i></button>
    </div>
  </div>
</div>
<?php endif; ?>

<div class="container">
  <?php
  $rows = [
      ['热门电影', $movies, 'category.php?type=movie'],
      ['热门剧集', $tvs, 'category.php?type=tv'],
      ['热门综艺', $shows, 'category.php?type=variety'],
      ['热门动漫', $animes, 'category.php?type=anime'],
  ];
  foreach ($rows as $ri => [$label, $list, $more]):
      if (!$list) continue;
  ?>
  <section class="section" style="animation:fadeInUp .5s <?= 100 + $ri * 80 ?>ms ease both">
    <div class="section-head">
      <h2 class="section-title"><?= e($label) ?></h2>
      <a class="section-more" href="<?= u($more) ?>">查看更多 <i class="ic ic-arrow-r" style="width:13px;height:13px"></i></a>
    </div>
    <div class="media-grid">
      <?php foreach ($list as $i => $m) echo media_card_html($m, $i); ?>
    </div>
  </section>
  <?php endforeach; ?>
</div>

<?php if ($showNotice): ?>
<div class="overlay" id="notice-overlay" data-version="<?= (int)$notice['id'] ?>">
  <div class="modal notice-modal">
    <div class="nm-icon"><i class="ic ic-horn"></i></div>
    <h3><?= e($notice['title'] ?: '网站公告') ?></h3>
    <div class="nm-content"><?= nl2br(e($notice['content'])) ?></div>
    <label class="nm-check"><input type="checkbox" id="notice-no-show"> 不再提示</label>
    <button class="btn btn-primary btn-block" id="notice-ok"><i class="ic ic-check"></i>我知道了</button>
  </div>
</div>
<?php endif; ?>

<?php require_once __DIR__ . '/includes/footer.php'; ?>

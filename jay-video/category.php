<?php
/** Jay影视 - 分类浏览（电影/剧集/综艺/动漫） */
require_once __DIR__ . '/includes/bootstrap.php';

$types = [
    'movie'   => ['电影', '/discover/movie', ['sort_by' => 'popularity.desc']],
    'tv'      => ['剧集', '/discover/tv', ['sort_by' => 'popularity.desc']],
    'variety' => ['综艺', '/discover/tv', ['with_genres' => '10764,10767', 'sort_by' => 'popularity.desc']],
    'anime'   => ['动漫', '/discover/tv', ['with_genres' => '16', 'sort_by' => 'popularity.desc']],
];
$type = $_GET['type'] ?? 'movie';
if (!isset($types[$type])) $type = 'movie';
[$typeName, $path, $params] = $types[$type];

$page = max(1, (int)($_GET['page'] ?? 1));
$params['page'] = $page;
$data = tmdb_get($path, $params, 1800);
$results = array_map('tmdb_norm', $data['results'] ?? []);
$results = array_values(array_filter($results, fn($m) => $m['id'] && $m['poster']));
$totalPages = min((int)($data['total_pages'] ?? 1), 500);
$base = u('category.php') . '?type=' . $type . '&';

$PAGE_TITLE = $typeName . ' - ' . site_name();
require_once __DIR__ . '/includes/header.php';
?>
<div class="container">
  <div class="page-head">
    <div>
      <h1><?= e($typeName) ?></h1>
      <p class="ph-sub">共收录 <?= (int)($data['total_results'] ?? count($results)) ?> 部作品 · 数据来源 TMDB</p>
    </div>
    <div class="tabs" style="margin:0">
      <a class="tab <?= $type === 'movie' ? 'active' : '' ?>" href="<?= u('category.php?type=movie') ?>">电影</a>
      <a class="tab <?= $type === 'tv' ? 'active' : '' ?>" href="<?= u('category.php?type=tv') ?>">剧集</a>
      <a class="tab <?= $type === 'variety' ? 'active' : '' ?>" href="<?= u('category.php?type=variety') ?>">综艺</a>
      <a class="tab <?= $type === 'anime' ? 'active' : '' ?>" href="<?= u('category.php?type=anime') ?>">动漫</a>
    </div>
  </div>

  <?php if ($results): ?>
  <div class="media-grid">
    <?php foreach ($results as $i => $m) echo media_card_html($m, $i); ?>
  </div>
  <?= pagination_html($base, $page, max(1, $totalPages)) ?>
  <?php else: ?>
  <div class="empty">
    <p>暂无数据，请稍后再试或检查后台 TMDB API Key 配置</p>
  </div>
  <?php endif; ?>
</div>
<?php require_once __DIR__ . '/includes/footer.php'; ?>

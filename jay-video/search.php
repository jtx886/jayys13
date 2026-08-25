<?php
/** Jay影视 - 搜索 */
require_once __DIR__ . '/includes/bootstrap.php';

$wd = trim($_GET['wd'] ?? '');
$page = max(1, (int)($_GET['page'] ?? 1));
$results = [];
$totalPages = 1;
$total = 0;

if ($wd !== '') {
    $data = tmdb_get('/search/multi', ['query' => $wd, 'page' => $page], 1800);
    foreach (($data['results'] ?? []) as $item) {
        $m = tmdb_norm($item);
        if ($m['id'] && $m['poster']) $results[] = $m;
    }
    $totalPages = min((int)($data['total_pages'] ?? 1), 500);
    $total = (int)($data['total_results'] ?? 0);
}
$base = u('search.php') . '?wd=' . rawurlencode($wd) . '&';

$PAGE_TITLE = ($wd !== '' ? '搜索：' . $wd . ' - ' : '搜索 - ') . site_name();
require_once __DIR__ . '/includes/header.php';
?>
<div class="container">
  <div class="page-head">
    <div>
      <h1>搜索结果</h1>
      <p class="ph-sub"><?= $wd !== '' ? '关键词「' . e($wd) . '」共找到 ' . $total . ' 个结果' : '请输入关键词搜索电影、剧集、动漫' ?></p>
    </div>
    <form action="<?= u('search.php') ?>" method="get" style="min-width:300px">
      <label class="search-box" style="padding:4px 8px">
                <input type="text" name="wd" value="<?= e($wd) ?>" placeholder="搜索片名，回车确认">
      </label>
    </form>
  </div>

  <?php if ($wd !== '' && !$results): ?>
  <div class="empty">
    <p>未找到与「<?= e($wd) ?>」相关的影视内容</p>
    <a class="btn btn-ghost" href="<?= u('index.php') ?>">回到首页</a>
  </div>
  <?php elseif ($results): ?>
  <div class="media-grid">
    <?php foreach ($results as $i => $m) echo media_card_html($m, $i); ?>
  </div>
  <?= pagination_html($base, $page, max(1, $totalPages)) ?>
  <?php else: ?>
  <div class="empty">
    <p>输入片名开始探索吧，支持电影、剧集、综艺、动漫</p>
  </div>
  <?php endif; ?>
</div>
<?php require_once __DIR__ . '/includes/footer.php'; ?>

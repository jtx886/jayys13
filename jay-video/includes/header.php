<?php
/** Jay影视 - 前台公共头部 */
if (!defined('JAY_BOOTSTRAP')) { exit('403'); }
$U = current_user();
$PAGE_TITLE = $PAGE_TITLE ?? site_name() . ' - 暗夜观影，光影随行';
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="description" content="<?= e(site_name()) ?> - 海量影视剧集在线观看，暗色现代观影体验">
<title><?= e($PAGE_TITLE) ?></title>
<link rel="stylesheet" href="<?= u('assets/css/style.css') ?>">
<?= theme_css() ?>
</head>
<body>
<header class="navbar">
  <div class="container navbar-inner">
    <a class="logo" href="<?= u('index.php') ?>">
      <span class="logo-mark"><i class="logo-play"></i></span>
      <span class="logo-text"><?= e(site_name()) ?></span>
    </a>
    <nav class="nav-menu" id="nav-menu">
      <a class="nav-link <?= nav_active('index') ?>" href="<?= u('index.php') ?>">首页</a>
      <a class="nav-link <?= nav_active('movie') ?>" href="<?= u('category.php?type=movie') ?>">电影</a>
      <a class="nav-link <?= nav_active('tv') ?>" href="<?= u('category.php?type=tv') ?>">剧集</a>
      <a class="nav-link <?= nav_active('variety') ?>" href="<?= u('category.php?type=variety') ?>">综艺</a>
      <a class="nav-link <?= nav_active('feedback') ?>" href="<?= u('feedback.php') ?>">反馈</a>
      <a class="nav-link <?= nav_active('anime') ?>" href="<?= u('category.php?type=anime') ?>">动漫</a>
    </nav>
    <div class="nav-actions">
      <form class="nav-search" action="<?= u('search.php') ?>" method="get">
        <label class="search-box">
                    <input type="text" name="wd" placeholder="搜索电影、剧集、动漫…" value="<?= e($_GET['wd'] ?? '') ?>" autocomplete="off">
        </label>
      </form>
      <?php if ($U): ?>
      <div class="nav-user" id="nav-user">
        <?= avatar_html($U) ?>
        <span class="nav-user-name"><?= e($U['username']) ?><?= $U['role'] === 'admin' ? ' <span class="badge-dev">开发者</span>' : '' ?></span>
        <div class="user-dropdown" id="user-dropdown">
          <a href="<?= u('profile.php') ?>">个人中心</a>
          <a href="<?= u('profile.php?tab=fav') ?>">我的收藏</a>
          <a href="<?= u('profile.php?tab=history') ?>">观看历史</a>
          <?php if ($U['role'] === 'admin'): ?>
          <a href="<?= u('admin/index.php') ?>">管理后台</a>
          <?php endif; ?>
          <a class="dd-danger" href="<?= u('logout.php') ?>">退出登录</a>
        </div>
      </div>
      <?php else: ?>
      <a class="btn btn-ghost btn-sm" href="<?= u('login.php') ?>">登录</a>
      <a class="btn btn-primary btn-sm" href="<?= u('register.php') ?>">注册</a>
      <?php endif; ?>
      <button class="icon-btn nav-toggle" aria-label="菜单">菜单</button>
    </div>
  </div>
</header>
<?= flash_html() ?>
<script>window.JAY_CSRF = "<?= e(csrf_token()) ?>";</script>

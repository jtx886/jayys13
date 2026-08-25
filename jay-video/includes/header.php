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
      <span class="logo-mark"><i class="ic ic-play" style="width:16px;height:16px"></i></span>
      <span class="logo-text"><?= e(site_name()) ?></span>
    </a>
    <nav class="nav-menu" id="nav-menu">
      <a class="nav-link <?= nav_active('index') ?>" href="<?= u('index.php') ?>"><i class="ic ic-home" style="width:15px;height:15px"></i>首页</a>
      <a class="nav-link <?= nav_active('movie') ?>" href="<?= u('category.php?type=movie') ?>"><i class="ic ic-film" style="width:15px;height:15px"></i>电影</a>
      <a class="nav-link <?= nav_active('tv') ?>" href="<?= u('category.php?type=tv') ?>"><i class="ic ic-tv" style="width:15px;height:15px"></i>剧集</a>
      <a class="nav-link <?= nav_active('variety') ?>" href="<?= u('category.php?type=variety') ?>"><i class="ic ic-mic" style="width:15px;height:15px"></i>综艺</a>
      <a class="nav-link <?= nav_active('feedback') ?>" href="<?= u('feedback.php') ?>"><i class="ic ic-chat" style="width:15px;height:15px"></i>反馈</a>
      <a class="nav-link <?= nav_active('anime') ?>" href="<?= u('category.php?type=anime') ?>"><i class="ic ic-star" style="width:15px;height:15px"></i>动漫</a>
    </nav>
    <div class="nav-actions">
      <form class="nav-search" action="<?= u('search.php') ?>" method="get">
        <label class="search-box">
          <i class="ic ic-search"></i>
          <input type="text" name="wd" placeholder="搜索电影、剧集、动漫…" value="<?= e($_GET['wd'] ?? '') ?>" autocomplete="off">
        </label>
      </form>
      <?php if ($U): ?>
      <div class="nav-user" id="nav-user">
        <?= avatar_html($U) ?>
        <span class="nav-user-name"><?= e($U['username']) ?><?= $U['role'] === 'admin' ? ' <span class="badge-dev">开发者</span>' : '' ?></span>
        <div class="user-dropdown" id="user-dropdown">
          <a href="<?= u('profile.php') ?>"><i class="ic ic-user"></i>个人中心</a>
          <a href="<?= u('profile.php?tab=fav') ?>"><i class="ic ic-heart"></i>我的收藏</a>
          <a href="<?= u('profile.php?tab=history') ?>"><i class="ic ic-clock"></i>观看历史</a>
          <?php if ($U['role'] === 'admin'): ?>
          <a href="<?= u('admin/index.php') ?>"><i class="ic ic-db"></i>管理后台</a>
          <?php endif; ?>
          <a class="dd-danger" href="<?= u('logout.php') ?>"><i class="ic ic-close"></i>退出登录</a>
        </div>
      </div>
      <?php else: ?>
      <a class="btn btn-ghost btn-sm" href="<?= u('login.php') ?>">登录</a>
      <a class="btn btn-primary btn-sm" href="<?= u('register.php') ?>">注册</a>
      <?php endif; ?>
      <button class="icon-btn nav-toggle" aria-label="菜单"><i class="ic ic-menu"></i></button>
    </div>
  </div>
</header>
<?= flash_html() ?>
<script>window.JAY_CSRF = "<?= e(csrf_token()) ?>";</script>

<?php
/** Jay影视 - 管理后台公共头部（含权限校验） */
require_once dirname(__DIR__) . '/includes/bootstrap.php';
$ADMIN = require_admin();
$ADMIN_PAGE = $ADMIN_PAGE ?? '';

$menu = [
    ['index', 'index.php', '仪表盘', 'ic-grid'],
    ['users', 'users.php', '用户管理', 'ic-user'],
    ['sources', 'sources.php', '播放源管理', 'ic-db'],
    ['mail', 'mail.php', '邮件推送', 'ic-mail'],
    ['notice', 'notice.php', '网站公告', 'ic-bell'],
    ['feedback', 'feedback.php', '反馈管理', 'ic-chat'],
    ['settings', 'settings.php', '网站设置', 'ic-sliders'],
];
$curMenu = null;
foreach ($menu as $m) {
    if ($m[0] === $ADMIN_PAGE) { $curMenu = $m; break; }
}
$PAGE_TITLE = ($curMenu ? $curMenu[2] . ' - ' : '') . '管理后台 - ' . site_name();
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title><?= e($PAGE_TITLE) ?></title>
<link rel="stylesheet" href="<?= u('assets/css/style.css') ?>">
<?= theme_css() ?>
</head>
<body>
<header class="navbar">
  <div class="container navbar-inner">
    <a class="logo" href="<?= u('admin/index.php') ?>">
      <span class="logo-mark"><i class="ic ic-play" style="width:16px;height:16px"></i></span>
      <span class="logo-text"><?= e(site_name()) ?></span>
      <span class="tag" style="font-size:11px;margin-left:4px">管理后台</span>
    </a>
    <nav class="nav-menu"></nav>
    <div class="nav-actions">
      <a class="btn btn-ghost btn-sm" href="<?= u('index.php') ?>"><i class="ic ic-home"></i>前台</a>
      <div class="nav-user" id="nav-user">
        <?= avatar_html($ADMIN) ?>
        <span class="nav-user-name"><?= e($ADMIN['username']) ?> <span class="badge-dev">开发者</span></span>
        <div class="user-dropdown" id="user-dropdown">
          <a href="<?= u('profile.php') ?>"><i class="ic ic-user"></i>个人中心</a>
          <a href="<?= u('index.php') ?>"><i class="ic ic-home"></i>返回前台</a>
          <a class="dd-danger" href="<?= u('logout.php') ?>"><i class="ic ic-close"></i>退出登录</a>
        </div>
      </div>
    </div>
  </div>
</header>
<?= flash_html() ?>
<script>window.JAY_CSRF = "<?= e(csrf_token()) ?>";</script>

<div class="admin-layout">
  <aside class="admin-side">
    <div class="side-label">功能菜单</div>
    <?php foreach ($menu as [$key, $file, $label, $icon]): ?>
    <a class="side-link <?= $ADMIN_PAGE === $key ? 'active' : '' ?>" href="<?= u('admin/' . $file) ?>">
      <i class="ic <?= $icon ?>"></i><?= e($label) ?>
    </a>
    <?php endforeach; ?>
  </aside>
  <main class="admin-main">

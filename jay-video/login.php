<?php
/** Jay影视 - 登录 */
require_once __DIR__ . '/includes/bootstrap.php';

if (is_logged_in()) redirect(u('index.php'));

$bannedMsg = $_SESSION['banned_msg'] ?? '';
unset($_SESSION['banned_msg']);
$fromPlay = ($_GET['from'] ?? '') === 'play';

$error = '';
if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $identifier = trim($_POST['identifier'] ?? '');
    $password = (string)($_POST['password'] ?? '');
    if ($identifier === '' || $password === '') {
        $error = '请输入账号与密码';
    } else {
        $user = db_one("SELECT * FROM users WHERE email=? OR username=? LIMIT 1", [$identifier, $identifier]);
        if (!$user || !password_verify($password, $user['password'])) {
            $error = '账号或密码错误';
        } elseif (user_banned($user)) {
            $error = '账号已被封禁：' . ($user['ban_reason'] ?: '违反社区规则') . '（' . ban_text($user) . '）';
        } else {
            // 封禁期已过自动解封
            if ((int)$user['status'] === 0) {
                db_x("UPDATE users SET status=1,ban_reason='',ban_start=NULL,ban_end=NULL WHERE id=?", [$user['id']]);
            }
            session_regenerate_id(true);
            $_SESSION['uid'] = (int)$user['id'];
            db_x("UPDATE users SET last_login=NOW() WHERE id=?", [$user['id']]);
            flash_set('欢迎回来，' . $user['username'] . '！', 'success');
            redirect(u('index.php'));
        }
    }
}

$PAGE_TITLE = '登录 - ' . site_name();
require_once __DIR__ . '/includes/header.php';
?>
<div class="auth-wrap">
  <div class="auth-left">
    <div class="auth-card">
      <h1>欢迎回来</h1>
      <p class="auth-sub">登录 <?= e(site_name()) ?>，继续你的观影之旅</p>

      <?php if ($bannedMsg): ?>
        <div class="auth-banner" style="background:rgba(239,68,68,.1);border-color:rgba(239,68,68,.35);color:#fca5a5">
          <i class="ic ic-lock"></i><span><?= e($bannedMsg) ?></span>
        </div>
      <?php elseif ($fromPlay): ?>
        <div class="auth-banner">
          <i class="ic ic-lock"></i><span>需要登录才可以观看哦，如没有账号请注册！</span>
        </div>
      <?php endif; ?>

      <?php if ($error): ?>
        <div class="auth-banner" style="background:rgba(239,68,68,.1);border-color:rgba(239,68,68,.35);color:#fca5a5">
          <i class="ic ic-info"></i><span><?= e($error) ?></span>
        </div>
      <?php endif; ?>

      <form method="post" action="">
        <?= csrf_field() ?>
        <div class="field">
          <label>邮箱 / 用户名</label>
          <input class="input" type="text" name="identifier" placeholder="请输入邮箱或用户名" value="<?= e($_POST['identifier'] ?? '') ?>" required autofocus>
        </div>
        <div class="field">
          <label>密码</label>
          <input class="input" type="password" name="password" placeholder="请输入密码" required>
        </div>
        <button class="btn btn-primary btn-block btn-lg" type="submit"><i class="ic ic-user"></i>登 录</button>
      </form>
      <div class="auth-links">还没有账号？<a href="<?= u('register.php') ?>">免费注册</a></div>
    </div>
  </div>
  <div class="auth-right">
    <div class="auth-quote">
      <h2>暗夜之中<br>光影随行</h2>
      <p>海量电影、剧集、综艺、动漫资源，多播放源智能解析，登录即刻开启沉浸式观影体验。</p>
      <div class="aq-features">
        <span class="aq-feat"><i class="ic ic-film"></i>多源播放</span>
        <span class="aq-feat"><i class="ic ic-heart"></i>收藏同步</span>
        <span class="aq-feat"><i class="ic ic-clock"></i>观看记录</span>
      </div>
    </div>
  </div>
</div>
<?php require_once __DIR__ . '/includes/footer.php'; ?>

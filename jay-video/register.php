<?php
/** Jay影视 - 注册（邮箱验证码） */
require_once __DIR__ . '/includes/bootstrap.php';

if (is_logged_in()) redirect(u('index.php'));

$error = '';
$old = ['email' => '', 'username' => ''];

if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $old['email'] = trim($_POST['email'] ?? '');
    $old['username'] = trim($_POST['username'] ?? '');
    $password = (string)($_POST['password'] ?? '');
    $confirm = (string)($_POST['confirm'] ?? '');
    $code = trim($_POST['code'] ?? '');

    if (!preg_match('/^[^@\s]+@[^@\s]+\.[^@\s]+$/', $old['email'])) {
        $error = '请输入正确的邮箱地址';
    } elseif (!preg_match('/^[\x{4e00}-\x{9fa5}A-Za-z0-9_]{2,20}$/u', $old['username'])) {
        $error = '用户名需为 2-20 位中文、字母、数字或下划线';
    } elseif (strlen($password) < 6 || strlen($password) > 64) {
        $error = '密码长度需为 6-64 位';
    } elseif ($password !== $confirm) {
        $error = '两次输入的密码不一致';
    } elseif (!preg_match('/^\d{6}$/', $code)) {
        $error = '请输入 6 位邮箱验证码';
    } elseif (db_val("SELECT id FROM users WHERE email=?", [$old['email']])) {
        $error = '该邮箱已被注册';
    } elseif (db_val("SELECT id FROM users WHERE username=?", [$old['username']])) {
        $error = '该用户名已被使用';
    } elseif (!verify_code_check($old['email'], $code)) {
        $error = '验证码错误或已过期';
    } else {
        db_x("INSERT INTO users (email,username,password,role,status,created_at) VALUES (?,?,?,'user',1,NOW())",
            [$old['email'], $old['username'], password_hash($password, PASSWORD_DEFAULT)]);
        $uid = (int)db_val("SELECT id FROM users WHERE email=?", [$old['email']]);
        session_regenerate_id(true);
        $_SESSION['uid'] = $uid;
        flash_set('注册成功，欢迎加入' . site_name() . '！', 'success');
        redirect(u('index.php'));
    }
}

$PAGE_TITLE = '注册 - ' . site_name();
require_once __DIR__ . '/includes/header.php';
?>
<div class="auth-wrap">
  <div class="auth-left">
    <div class="auth-card">
      <h1>创建账号</h1>
      <p class="auth-sub">注册后即可解锁播放、收藏与观看记录功能</p>

      <?php if ($error): ?>
        <div class="auth-banner" style="background:rgba(239,68,68,.1);border-color:rgba(239,68,68,.35);color:#fca5a5">
          <span><?= e($error) ?></span>
        </div>
      <?php endif; ?>

      <form method="post" action="" id="reg-form">
        <?= csrf_field() ?>
        <div class="field">
          <label>邮箱 <span class="req">*</span></label>
          <input class="input" type="email" id="reg-email" name="email" placeholder="用于接收验证码" value="<?= e($old['email']) ?>" required>
        </div>
        <div class="field">
          <label>邮箱验证码 <span class="req">*</span></label>
          <div class="input-group">
            <input class="input" type="text" name="code" maxlength="6" placeholder="6 位验证码" required>
            <button class="btn btn-ghost code-btn" type="button" onclick="sendVerifyCode(this)">获取验证码</button>
          </div>
          <p class="form-hint">验证码将通过 163 邮箱发送，10 分钟内有效</p>
        </div>
        <div class="field">
          <label>用户名 <span class="req">*</span></label>
          <input class="input" type="text" name="username" placeholder="2-20 位中文、字母、数字或下划线" value="<?= e($old['username']) ?>" required>
        </div>
        <div class="field">
          <label>密码 <span class="req">*</span></label>
          <input class="input" type="password" name="password" placeholder="至少 6 位" required>
        </div>
        <div class="field">
          <label>确认密码 <span class="req">*</span></label>
          <input class="input" type="password" name="confirm" placeholder="再次输入密码" required>
        </div>
        <button class="btn btn-primary btn-block btn-lg" type="submit">注 册</button>
      </form>
      <div class="auth-links">已有账号？<a href="<?= u('login.php') ?>">立即登录</a></div>
    </div>
  </div>
  <div class="auth-right">
    <div class="auth-quote">
      <h2>加入<?= e(site_name()) ?><br>好片一网打尽</h2>
      <p>注册即享多播放源解析、剧集季切换、观看进度保存、个性化收藏夹与公开反馈社区。</p>
      <div class="aq-features">
        <span class="aq-feat">邮箱验证</span>
        <span class="aq-feat">收藏夹</span>
        <span class="aq-feat">反馈社区</span>
      </div>
    </div>
  </div>
</div>
<?php require_once __DIR__ . '/includes/footer.php'; ?>

<?php
/** Jay影视 - 管理后台 · 用户管理（封禁/解封 + SMTP邮件通知） */
$ADMIN_PAGE = 'users';
require_once __DIR__ . '/_header.php';

/* ---------- 动作 ---------- */
if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $act = $_POST['act'] ?? '';
    $uid = (int)($_POST['uid'] ?? 0);
    $user = db_one("SELECT * FROM users WHERE id=?", [$uid]);

    if ($act === 'ban' && $user && $user['role'] !== 'admin') {
        $reason = trim($_POST['reason'] ?? '');
        $start  = trim($_POST['start'] ?? '');
        $end    = trim($_POST['end'] ?? '');
        $startDt = $start ? date('Y-m-d H:i:s', strtotime($start)) : date('Y-m-d H:i:s');
        $endDt   = $end ? date('Y-m-d H:i:s', strtotime($end)) : '';
        if ($reason === '') {
            flash_set('请填写封禁原因', 'error');
        } elseif ($endDt && strtotime($endDt) <= strtotime($startDt)) {
            flash_set('解除时间必须晚于封禁开始时间', 'error');
        } else {
            db_x("UPDATE users SET status=0,ban_reason=?,ban_start=?,ban_end=? WHERE id=?",
                [$reason, $startDt, $endDt, $uid]);
            $user['ban_reason'] = $reason;
            $user['ban_start'] = $startDt;
            $user['ban_end'] = $endDt ?: null;
            $mailRes = send_mail($user['email'], '【' . site_name() . '】账号封禁通知', mail_ban_notify($user));
            flash_set('已封禁用户 ' . $user['username']
                . ($mailRes[0] ? '，封禁通知邮件已发送' : '（封禁邮件发送失败：' . $mailRes[1] . '）'),
                $mailRes[0] ? 'success' : 'info');
        }
    }

    if ($act === 'unban' && $user) {
        db_x("UPDATE users SET status=1,ban_reason='',ban_start=NULL,ban_end=NULL WHERE id=?", [$uid]);
        flash_set('已解除 ' . $user['username'] . ' 的封禁', 'success');
    }

    redirect(u('admin/users.php' . (!empty($_POST['kw']) ? '?kw=' . rawurlencode($_POST['kw']) : '')));
}

/* ---------- 列表 ---------- */
$kw = trim($_GET['kw'] ?? '');
$where = '';
$params = [];
if ($kw !== '') {
    $where = "WHERE username LIKE ? OR email LIKE ?";
    $like = '%' . $kw . '%';
    $params = [$like, $like];
}
$page = max(1, (int)($_GET['page'] ?? 1));
$perPage = 20;
$total = (int)db_val("SELECT COUNT(*) FROM users {$where}", $params);
$totalPages = max(1, (int)ceil($total / $perPage));
$page = min($page, $totalPages);
$offset = ($page - 1) * $perPage;
$users = db_q("SELECT * FROM users {$where} ORDER BY id DESC LIMIT {$perPage} OFFSET {$offset}", $params);
$base = u('admin/users.php') . ($kw !== '' ? '?kw=' . rawurlencode($kw) . '&' : '?');
?>
<div class="admin-title">
  <div>
    <h1>用户管理</h1>
    <p class="at-sub">共 <?= $total ?> 个用户 · 支持封禁并邮件通知</p>
  </div>
  <form method="get" action="<?= u('admin/users.php') ?>" class="filter-bar" style="margin:0">
    <div class="field" style="min-width:230px">
      <label class="search-box"><i class="ic ic-search"></i><input type="text" name="kw" value="<?= e($kw) ?>" placeholder="搜索用户名 / 邮箱"></label>
    </div>
    <button class="btn btn-primary btn-sm" type="submit"><i class="ic ic-search"></i>搜索</button>
  </form>
</div>

<div class="panel">
  <div class="tbl-wrap">
    <table class="tbl">
      <thead><tr><th>用户</th><th>邮箱</th><th>状态</th><th>封禁信息</th><th>注册时间</th><th>最近登录</th><th style="text-align:right">操作</th></tr></thead>
      <tbody>
        <?php if ($users): foreach ($users as $v): $banned = user_banned($v); ?>
        <tr>
          <td>
            <div class="t-user">
              <?= avatar_html($v, 'avatar-sm') ?>
              <div><?= e($v['username']) ?><?= $v['role'] === 'admin' ? ' <span class="badge-dev">开发者</span>' : '' ?>
                <div class="t-sub"><?= $v['role'] === 'admin' ? '管理员' : '普通用户' ?> · #<?= (int)$v['id'] ?></div>
              </div>
            </div>
          </td>
          <td style="color:var(--text-2)"><?= e($v['email']) ?></td>
          <td><?= $banned ? '<span class="status-dot banned">封禁中</span>' : '<span class="status-dot">正常</span>' ?></td>
          <td style="max-width:220px">
            <?php if ((int)$v['status'] === 0): ?>
              <div class="t-sub" style="color:#f87171"><?= e($v['ban_reason'] ?: '未填写原因') ?></div>
              <div class="t-sub"><?= e($v['ban_start'] ?: '立即生效') ?> ~ <?= e($v['ban_end'] ?: '无限期') ?></div>
            <?php else: ?><span style="color:var(--text-3)">—</span><?php endif; ?>
          </td>
          <td style="color:var(--text-3)"><?= e($v['created_at']) ?></td>
          <td style="color:var(--text-3)"><?= e($v['last_login'] ?: '从未登录') ?></td>
          <td style="text-align:right">
            <?php if ($v['role'] === 'admin'): ?>
              <span class="tag-gray">管理员不可封禁</span>
            <?php elseif ($banned || (int)$v['status'] === 0): ?>
              <form method="post" action="<?= u('admin/users.php') ?>" style="display:inline" data-confirm="确定解除该用户封禁吗？">
                <?= csrf_field() ?>
                <input type="hidden" name="act" value="unban"><input type="hidden" name="uid" value="<?= (int)$v['id'] ?>">
                <button class="btn btn-green btn-xs" type="submit"><i class="ic ic-check"></i>解封</button>
              </form>
              <button class="btn btn-ghost btn-xs" type="button" onclick="openBan(<?= (int)$v['id'] ?>, '<?= e($v['username']) ?>', '<?= e($v['ban_reason']) ?>', '<?= e($v['ban_start']) ?>', '<?= e($v['ban_end']) ?>')"><i class="ic ic-edit"></i>修改</button>
            <?php else: ?>
              <button class="btn btn-danger btn-xs" type="button" onclick="openBan(<?= (int)$v['id'] ?>, '<?= e($v['username']) ?>', '', '', '')"><i class="ic ic-lock"></i>封禁</button>
            <?php endif; ?>
          </td>
        </tr>
        <?php endforeach; else: ?>
        <tr><td colspan="7" style="text-align:center;color:var(--text-3);padding:34px">未找到匹配的用户</td></tr>
        <?php endif; ?>
      </tbody>
    </table>
  </div>
</div>
<?= pagination_html($base, $page, $totalPages) ?>

<!-- 封禁弹窗 -->
<div class="overlay" id="ban-overlay">
  <div class="modal">
    <div class="modal-head">
      <h3><i class="ic ic-lock"></i>封禁用户 <span id="ban-uname" style="color:var(--primary-2)"></span></h3>
      <button class="modal-close" type="button" data-close><i class="ic ic-close"></i></button>
    </div>
    <form method="post" action="<?= u('admin/users.php') ?>">
      <?= csrf_field() ?>
      <input type="hidden" name="act" value="ban">
      <input type="hidden" name="uid" id="ban-uid" value="">
      <div class="field">
        <label>封禁原因（将写入邮件通知用户）<span class="req">*</span></label>
        <textarea class="textarea" name="reason" id="ban-reason" required placeholder="例如：发布违规内容"></textarea>
      </div>
      <div class="field">
        <label>封禁开始时间（默认立即生效）</label>
        <input class="input" type="datetime-local" name="start" id="ban-start">
      </div>
      <div class="field">
        <label>解除封禁时间（留空表示无限期）</label>
        <input class="input" type="datetime-local" name="end" id="ban-end">
      </div>
      <p class="form-hint" style="margin-bottom:16px"><i class="ic ic-mail" style="width:13px;height:13px"></i> 封禁后将自动通过 163 SMTP 向该用户邮箱发送封禁通知</p>
      <button class="btn btn-danger btn-block" type="submit"><i class="ic ic-lock"></i>确认封禁</button>
    </form>
  </div>
</div>

<script>
function openBan(id, name, reason, start, end) {
  document.getElementById('ban-uid').value = id;
  document.getElementById('ban-uname').textContent = name;
  document.getElementById('ban-reason').value = reason || '';
  var s = document.getElementById('ban-start');
  var e = document.getElementById('ban-end');
  var fmt = function (v) { return v ? v.slice(0, 16).replace(' ', 'T') : ''; };
  if (reason) {
    s.value = fmt(start);
    e.value = fmt(end);
  } else {
    var now = new Date(Date.now() - new Date().getTimezoneOffset() * 60000);
    s.value = now.toISOString().slice(0, 16);
    e.value = '';
  }
  document.getElementById('ban-overlay').classList.add('show');
}
</script>
<?php require_once __DIR__ . '/_footer.php'; ?>

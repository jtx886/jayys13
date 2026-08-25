<?php
/** Jay影视 - 管理后台 · 邮件推送（163 SMTP） */
$ADMIN_PAGE = 'mail';
require_once __DIR__ . '/_header.php';

$results = null;

if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $act = $_POST['act'] ?? '';

    if ($act === 'send') {
        $subject = trim($_POST['subject'] ?? '');
        $content = trim($_POST['content'] ?? '');
        $target  = $_POST['target'] ?? 'all';
        $email   = trim($_POST['email'] ?? '');

        if ($subject === '' || $content === '') {
            flash_set('请填写邮件主题与内容', 'error');
        } else {
            if ($target === 'one') {
                if (!preg_match('/^[^@\s]+@[^@\s]+\.[^@\s]+$/', $email)) {
                    flash_set('请输入正确的收件邮箱', 'error');
                } else {
                    $html = mail_wrap($subject, '<p style="color:#c8d0de;font-size:14px;line-height:1.9;white-space:pre-wrap;">' . e($content) . '</p>');
                    $r = send_mail($email, $subject, $html);
                    $results = [['email' => $email, 'ok' => $r[0], 'err' => $r[1]]];
                }
            } else {
                @set_time_limit(0);
                $users = db_q("SELECT email,username FROM users WHERE role<>'admin' OR id=?", [(int)$ADMIN['id']]);
                $results = [];
                $i = 0;
                foreach ($users as $v) {
                    $html = mail_wrap($subject,
                        '<p style="color:#93a0b4;font-size:14px;">您好，<b style="color:#f0f3fa">' . e($v['username']) . '</b>：</p>'
                        . '<p style="color:#c8d0de;font-size:14px;line-height:1.9;white-space:pre-wrap;">' . e($content) . '</p>');
                    $r = send_mail($v['email'], $subject, $html);
                    $results[] = ['email' => $v['email'], 'ok' => $r[0], 'err' => $r[1]];
                    $i++;
                    if ($i % 5 === 0) usleep(300000); // 轻微限速，避免 SMTP 频率限制
                    if (count($results) >= 200) break; // 单次最多200封
                }
            }
            $okCount = count(array_filter($results, fn($r) => $r['ok']));
            flash_set('邮件推送完成：成功 ' . $okCount . ' 封 / 失败 ' . (count($results) - $okCount) . ' 封', $okCount > 0 ? 'success' : 'error');
        }
        redirect(u('admin/mail.php'));
    }
}

$mailLogs = db_q("SELECT * FROM mail_log ORDER BY id DESC LIMIT 30");
?>
<div class="admin-title">
  <div>
    <h1>邮件推送</h1>
    <p class="at-sub">通过 163 SMTP 向用户邮箱发送自定义通知（支持换行，内容将以文本形式呈现）</p>
  </div>
</div>

<div class="dash-grid">
  <div class="panel">
    <div class="panel-head"><h3>撰写邮件</h3></div>
    <div class="panel-body">
      <form method="post" action="<?= u('admin/mail.php') ?>">
        <?= csrf_field() ?>
        <input type="hidden" name="act" value="send">
        <div class="field">
          <label>发送对象</label>
          <div class="radio-cards">
            <label class="radio-card checked"><input type="radio" name="target" value="all" checked onchange="toggleTarget(this.value)">全部用户</label>
            <label class="radio-card"><input type="radio" name="target" value="one" onchange="toggleTarget(this.value)">指定邮箱</label>
          </div>
        </div>
        <div class="field" id="one-field" style="display:none">
          <label>收件邮箱</label>
          <input class="input" type="email" name="email" placeholder="user@example.com">
        </div>
        <div class="field">
          <label>邮件主题 <span class="req">*</span></label>
          <input class="input" type="text" name="subject" maxlength="100" placeholder="例如：新片上线通知" required>
        </div>
        <div class="field">
          <label>邮件内容 <span class="req">*</span></label>
          <textarea class="textarea" name="content" style="min-height:170px" placeholder="亲爱的用户：&#10;&#10;……&#10;&#10;——<?= e(site_name()) ?>" required></textarea>
        </div>
        <button class="btn btn-primary btn-block" type="submit" data-confirm="确认发送邮件吗？">立即发送</button>
      </form>
    </div>
  </div>

  <div class="panel">
    <div class="panel-head"><h3>发信记录（最近 30 条）</h3></div>
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th>收件邮箱</th><th>主题</th><th>状态</th><th>时间</th></tr></thead>
        <tbody>
          <?php if ($mailLogs): foreach ($mailLogs as $l): ?>
          <tr>
            <td style="color:var(--text-2)"><?= e($l['email']) ?></td>
            <td><?= e($l['subject']) ?><?= $l['error'] ? '<div class="t-sub" style="color:#f87171">' . e($l['error']) . '</div>' : '' ?></td>
            <td><?= (int)$l['status'] === 1 ? '<span class="tag-green">成功</span>' : '<span class="tag-red">失败</span>' ?></td>
            <td style="color:var(--text-3)"><?= e($l['created_at']) ?></td>
          </tr>
          <?php endforeach; else: ?>
          <tr><td colspan="4" style="text-align:center;color:var(--text-3);padding:30px">暂无发信记录</td></tr>
          <?php endif; ?>
        </tbody>
      </table>
    </div>
  </div>
</div>

<script>
function toggleTarget(v) {
  document.getElementById('one-field').style.display = v === 'one' ? 'block' : 'none';
  document.querySelectorAll('.radio-card').forEach(function (c) {
    var input = c.querySelector('input');
    if (input) c.classList.toggle('checked', input.checked);
  });
}
</script>
<?php require_once __DIR__ . '/_footer.php'; ?>

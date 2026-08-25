<?php
/** Jay影视 - 管理后台 · 反馈详情（管理员回复 / 删除回复） */
require_once __DIR__ . '/_header.php';

$fid = (int)($_GET['id'] ?? 0);
$fb = db_one(
    "SELECT f.*, u.username, u.avatar, u.role
     FROM feedbacks f LEFT JOIN users u ON u.id=f.user_id
     WHERE f.id=?", [$fid]);
if (!$fb) {
    flash_set('反馈不存在', 'error');
    redirect(u('admin/feedback.php'));
}

if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $act = $_POST['act'] ?? '';

    if ($act === 'reply') {
        $content = trim($_POST['content'] ?? '');
        if (mb_strlen($content) < 1) {
            flash_set('回复内容不能为空', 'error');
        } else {
            db_x("INSERT INTO feedback_replies (feedback_id,user_id,content,created_at) VALUES (?,?,?,NOW())",
                [$fid, (int)$ADMIN['id'], $content]);
            flash_set('管理员回复已发布', 'success');
        }
    }

    if ($act === 'del_reply') {
        db_x("DELETE FROM feedback_replies WHERE id=?", [(int)($_POST['rid'] ?? 0)]);
        flash_set('回复已删除', 'info');
    }

    redirect(u('admin/feedback_view.php?id=' . $fid));
}

$replies = db_q(
    "SELECT r.*, u.username, u.avatar, u.role
     FROM feedback_replies r LEFT JOIN users u ON u.id=r.user_id
     WHERE r.feedback_id=?
     ORDER BY (u.role='admin') DESC, r.created_at ASC, r.id ASC", [$fid]);
?>
<div class="admin-title">
  <div>
    <h1>反馈详情</h1>
    <p class="at-sub"><a href="<?= u('admin/feedback.php') ?>" style="color:var(--primary-2)">← 返回反馈列表</a></p>
  </div>
</div>

<div class="panel">
  <div class="panel-head"><h3><i class="ic ic-chat"></i>用户反馈</h3></div>
  <div class="panel-body">
    <div class="fb-item-head">
      <?= avatar_html(['username' => $fb['username'] ?? '已注销', 'avatar' => $fb['avatar'] ?? '', 'role' => $fb['role'] ?? 'user'], 'avatar-lg') ?>
      <div style="flex:1;min-width:0">
        <div class="fb-item-meta" style="font-size:14px"><span class="author"><?= name_html($fb['username'] ? $fb : null) ?></span></div>
        <div class="fb-item-meta" style="margin-top:3px"><i class="ic ic-clock" style="width:12px;height:12px"></i> <?= e($fb['created_at']) ?> · 点赞 <?= (int)$fb['likes'] ?></div>
      </div>
    </div>
    <h2 class="fb-item-title" style="font-size:18px;margin-top:14px"><?= e($fb['title']) ?></h2>
    <p class="fb-content"><?= e($fb['content']) ?></p>
  </div>
</div>

<div class="panel">
  <div class="panel-head"><h3><i class="ic ic-edit"></i>以管理员身份回复</h3></div>
  <div class="panel-body">
    <form method="post" action="<?= u('admin/feedback_view.php?id=' . $fid) ?>">
      <?= csrf_field() ?>
      <input type="hidden" name="act" value="reply">
      <textarea class="textarea" name="content" placeholder="输入官方回复内容…" required></textarea>
      <div style="text-align:right;margin-top:12px">
        <button class="btn btn-primary" type="submit"><i class="ic ic-send"></i>发布官方回复</button>
      </div>
    </form>
  </div>
</div>

<div class="panel">
  <div class="panel-head"><h3><i class="ic ic-chat"></i>全部回复（<?= count($replies) ?> 条 · 管理员优先展示）</h3></div>
  <div class="panel-body">
    <?php if ($replies): ?>
    <div class="reply-list">
      <?php foreach ($replies as $r): ?>
      <div class="reply-item <?= ($r['role'] ?? '') === 'admin' ? 'is-admin' : '' ?>">
        <?= avatar_html(['username' => $r['username'] ?? '已注销', 'avatar' => $r['avatar'] ?? '', 'role' => $r['role'] ?? 'user'], 'avatar-sm') ?>
        <div class="reply-bubble">
          <div class="reply-head">
            <span class="r-author"><?= name_html($r['username'] ? $r : null) ?></span>
            <span><?= e(time_ago($r['created_at'])) ?></span>
            <form method="post" action="<?= u('admin/feedback_view.php?id=' . $fid) ?>" style="margin-left:auto" data-confirm="确定删除该回复吗？">
              <?= csrf_field() ?>
              <input type="hidden" name="act" value="del_reply"><input type="hidden" name="rid" value="<?= (int)$r['id'] ?>">
              <button class="btn btn-danger btn-xs" type="submit"><i class="ic ic-trash"></i>删除</button>
            </form>
          </div>
          <div class="reply-content"><?= e($r['content']) ?></div>
        </div>
      </div>
      <?php endforeach; ?>
    </div>
    <?php else: ?>
    <div class="empty" style="padding:26px"><div class="empty-icon"><i class="ic ic-chat"></i></div><p>暂无回复</p></div>
    <?php endif; ?>
  </div>
</div>
<?php require_once __DIR__ . '/_footer.php'; ?>

<?php
/** Jay影视 - 反馈详情（管理员回复优先展示 / 超3条折叠） */
require_once __DIR__ . '/includes/bootstrap.php';

$U = current_user();
$fid = (int)($_GET['id'] ?? 0);

$fb = db_one(
    "SELECT f.*, u.username, u.avatar, u.role,
            (SELECT COUNT(*) FROM feedback_likes l WHERE l.feedback_id=f.id AND l.user_id=" . (int)($U['id'] ?? 0) . ") AS liked
     FROM feedbacks f LEFT JOIN users u ON u.id=f.user_id
     WHERE f.id=? AND f.is_public=1", [$fid]);
if (!$fb) {
    flash_set('反馈不存在或未公开', 'error');
    redirect(u('feedback.php'));
}

/* 提交回复 */
if (is_post()) {
    if (!$U) { flash_set('请先登录后再回复', 'error'); redirect(u('login.php')); }
    if (!csrf_check()) csrf_fail();
    $content = trim($_POST['content'] ?? '');
    if (mb_strlen($content) < 1 || mb_strlen($content) > 2000) {
        flash_set('回复内容长度不合法', 'error');
    } else {
        db_x("INSERT INTO feedback_replies (feedback_id,user_id,content,created_at) VALUES (?,?,?,NOW())",
            [$fid, (int)$U['id'], $content]);
        flash_set('回复成功', 'success');
    }
    redirect(u('feedback_view.php?id=' . $fid));
}

/* 回复排序：管理员回复在前（各自按时间正序），普通用户在后 */
$replies = db_q(
    "SELECT r.*, u.username, u.avatar, u.role
     FROM feedback_replies r LEFT JOIN users u ON u.id=r.user_id
     WHERE r.feedback_id=?
     ORDER BY (u.role='admin') DESC, r.created_at ASC, r.id ASC", [$fid]);
$replyCount = count($replies);

$PAGE_TITLE = $fb['title'] . ' - 反馈详情 - ' . site_name();
require_once __DIR__ . '/includes/header.php';
?>
<div class="container">
  <div class="crumb">
    <a href="<?= u('index.php') ?>">首页</a><span>/</span>
    <a href="<?= u('feedback.php') ?>">反馈中心</a><span>/</span>
    <span style="color:var(--text-2)"><?= e(mb_substr($fb['title'], 0, 20)) ?></span>
  </div>

  <div class="fb-card">
    <div class="fb-item-head">
      <?= avatar_html(['username' => $fb['username'] ?? '已注销', 'avatar' => $fb['avatar'] ?? '', 'role' => $fb['role'] ?? 'user'], 'avatar-lg') ?>
      <div style="flex:1;min-width:0">
        <div class="fb-item-meta" style="font-size:14px">
          <span class="author"><?= name_html($fb['username'] ? $fb : null) ?></span>
        </div>
        <div class="fb-item-meta" style="margin-top:3px"><?= e($fb['created_at']) ?></div>
      </div>
    </div>
    <h2 class="fb-item-title" style="font-size:19px;margin-top:16px"><?= e($fb['title']) ?></h2>
    <p class="fb-content" style="font-size:14.5px;line-height:1.9"><?= e($fb['content']) ?></p>
    <div class="fb-actions">
      <button class="fb-act like-btn <?= $fb['liked'] ? 'liked' : '' ?>" data-id="<?= (int)$fb['id'] ?>">
        赞 <span><?= (int)$fb['likes'] ?></span>
      </button>
      <span class="fb-act" style="cursor:default"><?= $replyCount ?> 条回复</span>
    </div>

    <?php if ($replies): ?>
    <div class="reply-list">
      <?php foreach ($replies as $r): ?>
      <div class="reply-item <?= ($r['role'] ?? '') === 'admin' ? 'is-admin' : '' ?> <?= (int)$r['user_id'] === (int)$fb['user_id'] ? 'is-op' : '' ?>">
        <?= avatar_html(['username' => $r['username'] ?? '已注销', 'avatar' => $r['avatar'] ?? '', 'role' => $r['role'] ?? 'user'], 'avatar-sm') ?>
        <div class="reply-bubble">
          <div class="reply-head">
            <span class="r-author"><?= name_html($r['username'] ? $r : null) ?></span>
            <?php if ((int)$r['user_id'] === (int)$fb['user_id'] && ($r['role'] ?? '') !== 'admin'): ?><span class="tag-gray" style="font-size:11px;padding:0 8px">提问者</span><?php endif; ?>
            <span><?= e(time_ago($r['created_at'])) ?></span>
          </div>
          <div class="reply-content"><?= e($r['content']) ?></div>
        </div>
      </div>
      <?php endforeach; ?>
      <?php if ($replyCount > 3): ?>
      <button class="reply-toggle" data-total="<?= $replyCount ?>" type="button"></button>
      <?php endif; ?>
    </div>
    <?php endif; ?>

    <?php if ($U): ?>
    <form class="reply-form" method="post" action="<?= u('feedback_view.php?id=' . $fid) ?>">
      <?= csrf_field() ?>
      <?= avatar_html($U, 'avatar-sm') ?>
      <div style="flex:1">
        <textarea class="textarea" name="content" placeholder="友善回复，理性讨论…" style="min-height:64px" required></textarea>
        <div style="text-align:right;margin-top:10px">
          <button class="btn btn-primary btn-sm" type="submit">回复</button>
        </div>
      </div>
    </form>
    <?php else: ?>
    <div style="margin-top:18px;padding:16px;border:1px dashed var(--border-2);border-radius:12px;text-align:center">
      <p style="color:var(--text-3);font-size:13px;margin-bottom:12px">登录后即可回复与点赞</p>
      <a class="btn btn-primary btn-sm" href="<?= u('login.php') ?>">去登录</a>
    </div>
    <?php endif; ?>
  </div>

  <div style="margin-top:22px;text-align:center">
    <a class="btn btn-ghost" href="<?= u('feedback.php') ?>">返回反馈列表</a>
  </div>
</div>
<?php require_once __DIR__ . '/includes/footer.php'; ?>

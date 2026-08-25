<?php
/** Jay影视 - AJAX 接口（验证码 / 点赞 / 观看进度） */
require_once __DIR__ . '/includes/bootstrap.php';

$action = $_GET['action'] ?? $_POST['action'] ?? '';

switch ($action) {

    /* ---------- 发送注册验证码 ---------- */
    case 'send_code':
        if (!is_post() || !csrf_check()) json_out(['ok' => false, 'msg' => '请求校验失败，请刷新页面']);
        $email = trim($_POST['email'] ?? '');
        if (!preg_match('/^[^@\s]+@[^@\s]+\.[^@\s]+$/', $email)) {
            json_out(['ok' => false, 'msg' => '邮箱格式不正确']);
        }
        if (db_val("SELECT id FROM users WHERE email=?", [$email])) {
            json_out(['ok' => false, 'msg' => '该邮箱已注册，请直接登录']);
        }
        [$ok, $msg] = verify_code_create($email);
        json_out(['ok' => $ok, 'msg' => $msg]);

    /* ---------- 反馈点赞 ---------- */
    case 'like':
        if (!is_post() || !csrf_check()) json_out(['ok' => false, 'msg' => '请求校验失败']);
        $U = current_user();
        if (!$U) json_out(['ok' => false, 'msg' => '请先登录']);
        $fid = (int)($_POST['feedback_id'] ?? 0);
        $fb = db_one("SELECT id FROM feedbacks WHERE id=? AND is_public=1", [$fid]);
        if (!$fb) json_out(['ok' => false, 'msg' => '反馈不存在']);
        $uid = (int)$U['id'];
        if (db_val("SELECT id FROM feedback_likes WHERE feedback_id=? AND user_id=?", [$fid, $uid])) {
            db_x("DELETE FROM feedback_likes WHERE feedback_id=? AND user_id=?", [$fid, $uid]);
            db_x("UPDATE feedbacks SET likes=GREATEST(likes-1,0) WHERE id=?", [$fid]);
            $liked = false;
        } else {
            db_x("INSERT IGNORE INTO feedback_likes (feedback_id,user_id,created_at) VALUES (?,?,NOW())", [$fid, $uid]);
            db_x("UPDATE feedbacks SET likes=likes+1 WHERE id=?", [$fid]);
            $liked = true;
        }
        $count = (int)db_val("SELECT likes FROM feedbacks WHERE id=?", [$fid]);
        json_out(['ok' => true, 'liked' => $liked, 'count' => $count]);

    /* ---------- 保存观看进度 ---------- */
    case 'progress':
        if (!is_post()) json_out(['ok' => false]);
        $U = current_user();
        if (!$U) json_out(['ok' => false, 'msg' => '未登录']);
        if (!csrf_check()) json_out(['ok' => false]);
        $tmdbId = (int)($_POST['id'] ?? 0);
        $type = ($_POST['type'] ?? 'movie') === 'tv' ? 'tv' : 'movie';
        $season = max(1, (int)($_POST['season'] ?? 1));
        $episode = max(1, (int)($_POST['episode'] ?? 1));
        $seconds = max(0, min(86400 * 7, (int)($_POST['seconds'] ?? 0)));
        $title = mb_substr(trim($_POST['title'] ?? ''), 0, 200);
        $poster = mb_substr(trim($_POST['poster'] ?? ''), 0, 480);
        $epname = mb_substr(trim($_POST['epname'] ?? ''), 0, 200);
        if ($tmdbId <= 0) json_out(['ok' => false]);
        db_x("INSERT INTO watch_history (user_id,tmdb_id,media_type,title,poster,season,episode,episode_name,position_seconds,created_at,updated_at)
              VALUES (?,?,?,?,?,?,?,?,?,NOW(),NOW())
              ON DUPLICATE KEY UPDATE position_seconds=GREATEST(position_seconds,VALUES(position_seconds)),
              title=VALUES(title),poster=VALUES(poster),episode_name=VALUES(episode_name),updated_at=NOW()",
            [(int)$U['id'], $tmdbId, $type, $title, $poster, $season, $episode, $epname, $seconds]);
        json_out(['ok' => true]);

    default:
        json_out(['ok' => false, 'msg' => '未知操作']);
}

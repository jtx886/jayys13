<?php
/**
 * Jay影视 - 公共函数库
 */

/* ---------- 基础 ---------- */
function e($str): string
{
    return htmlspecialchars((string)$str, ENT_QUOTES, 'UTF-8');
}

function u(string $path = ''): string
{
    $base = defined('BASE_URL') ? BASE_URL : '';
    return $base . ltrim($path, '/');
}

function redirect(string $url): void
{
    header('Location: ' . $url);
    exit;
}

function json_out(array $arr): void
{
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($arr, JSON_UNESCAPED_UNICODE);
    exit;
}

function is_post(): bool
{
    return ($_SERVER['REQUEST_METHOD'] ?? '') === 'POST';
}

/* ---------- 设置 ---------- */
function setting_all(): array
{
    static $cache = null;
    if ($cache === null) {
        $cache = [];
        foreach (db_q("SELECT skey,svalue FROM settings") as $r) {
            $cache[$r['skey']] = $r['svalue'];
        }
    }
    return $cache;
}

function setting(string $key, string $default = ''): string
{
    $all = setting_all();
    return isset($all[$key]) && $all[$key] !== '' ? $all[$key] : $default;
}

function save_setting(string $key, string $value): void
{
    db_x("INSERT INTO settings (skey,svalue) VALUES (?,?) ON DUPLICATE KEY UPDATE svalue=VALUES(svalue)", [$key, $value]);
}

function site_name(): string
{
    return setting('site_name', 'Jay影视');
}

/* ---------- 主题 ---------- */
function theme_rgb(string $hex): array
{
    $hex = ltrim($hex, '#');
    if (strlen($hex) === 3) {
        $hex = $hex[0] . $hex[0] . $hex[1] . $hex[1] . $hex[2] . $hex[2];
    }
    if (!preg_match('/^[0-9a-fA-F]{6}$/', $hex)) $hex = 'e50914';
    return [hexdec(substr($hex, 0, 2)), hexdec(substr($hex, 2, 2)), hexdec(substr($hex, 4, 2))];
}

function theme_css(): string
{
    $color = setting('theme_color', '#e50914');
    [$r, $g, $b] = theme_rgb($color);
    $d = function ($p) use ($r, $g, $b) {
        $f = fn($c) => max(0, min(255, (int)round($c * $p)));
        return sprintf('#%02x%02x%02x', $f($r), $f($g), $f($b));
    };
    $a = fn($al) => sprintf('rgba(%d,%d,%d,%s)', $r, $g, $b, $al);
    return '<style id="theme-vars">:root{--primary:' . $d(1) . ';--primary-2:' . $d(1.28) . ';'
        . '--primary-90:' . $a('.9') . ';--primary-60:' . $a('.6') . ';--primary-30:' . $a('.3') . ';'
        . '--primary-15:' . $a('.15') . ';--primary-08:' . $a('.08') . ';}</style>';
}

/* ---------- CSRF ---------- */
function csrf_token(): string
{
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(16));
    }
    return $_SESSION['csrf'];
}

function csrf_field(): string
{
    return '<input type="hidden" name="csrf" value="' . e(csrf_token()) . '">';
}

function csrf_check(): bool
{
    $t = $_POST['csrf'] ?? ($_SERVER['HTTP_X_CSRF'] ?? '');
    return is_string($t) && $t !== '' && hash_equals($_SESSION['csrf'] ?? '', $t);
}

function csrf_fail(): void
{
    flash_set('安全校验失败，请刷新页面重试', 'error');
    header('Location: ' . ($_SERVER['HTTP_REFERER'] ?? u('index.php')));
    exit;
}

/* ---------- Flash ---------- */
function flash_set(string $msg, string $type = 'success'): void
{
    $_SESSION['flash'] = ['msg' => $msg, 'type' => $type];
}

function flash_html(): string
{
    if (empty($_SESSION['flash'])) return '';
    $f = $_SESSION['flash'];
    unset($_SESSION['flash']);
    return '<div id="flash-data" data-msg="' . e($f['msg']) . '" data-type="' . e($f['type']) . '"></div>';
}

/* ---------- 用户与权限 ---------- */
function current_user(): ?array
{
    static $user = false;
    if ($user === false) {
        $user = null;
        if (!empty($_SESSION['uid'])) {
            $row = db_one("SELECT * FROM users WHERE id=?", [(int)$_SESSION['uid']]);
            if ($row) $user = $row;
        }
    }
    return $user;
}

function is_logged_in(): bool
{
    return current_user() !== null;
}

function is_admin(): bool
{
    $u = current_user();
    return $u !== null && $u['role'] === 'admin';
}

function require_login(): array
{
    $u = current_user();
    if ($u === null) {
        flash_set('需要登录才可以观看哦，如没有账号请注册！', 'error');
        redirect(u('login.php'));
    }
    return $u;
}

function require_admin(): array
{
    $u = current_user();
    if ($u === null || $u['role'] !== 'admin') {
        flash_set('无权访问管理后台', 'error');
        redirect(u('index.php'));
    }
    return $u;
}

/** 是否处于封禁期 */
function user_banned(array $user): bool
{
    if ((int)$user['status'] === 1) return false;
    $now = time();
    $start = $user['ban_start'] ? strtotime($user['ban_start']) : 0;
    $end = $user['ban_end'] ? strtotime($user['ban_end']) : 0;
    if ($start && $now < $start) return false;   // 封禁尚未开始
    if ($end && $now > $end) return false;       // 封禁已过期
    return true;
}

/** 封禁剩余描述 */
function ban_text(array $user): string
{
    $start = $user['ban_start'] ?: '立即生效';
    $end = $user['ban_end'] ?: '无限期';
    return $start . ' ~ ' . $end;
}

/* ---------- 展示辅助 ---------- */
function avatar_html(?array $user, string $cls = ''): string
{
    if ($user && !empty($user['avatar']) && is_file(ROOT . '/' . $user['avatar'])) {
        return '<span class="avatar ' . $cls . '"><img src="' . u($user['avatar']) . '?v=' . filemtime(ROOT . '/' . $user['avatar']) . '" alt="头像"></span>';
    }
    $name = $user ? $user['username'] : '?';
    return '<span class="avatar ' . $cls . '">' . e(mb_substr($name, 0, 1)) . '</span>';
}

function name_html(?array $user): string
{
    if (!$user) return '未知用户';
    $html = '<span class="nu-name">' . e($user['username']) . '</span>';
    if ($user['role'] === 'admin') $html .= ' <span class="badge-dev">开发者</span>';
    return $html;
}

function time_ago(string $datetime): string
{
    $t = strtotime($datetime);
    if (!$t) return '';
    $diff = time() - $t;
    if ($diff < 60) return '刚刚';
    if ($diff < 3600) return floor($diff / 60) . ' 分钟前';
    if ($diff < 86400) return floor($diff / 3600) . ' 小时前';
    if ($diff < 2592000) return floor($diff / 86400) . ' 天前';
    return date('Y-m-d', $t);
}

function format_seconds(int $s): string
{
    if ($s < 60) return $s . '秒';
    $m = floor($s / 60);
    $sec = $s % 60;
    if ($m < 60) return $m . '分' . ($sec > 0 ? $sec . '秒' : '');
    $h = floor($m / 60);
    return $h . '小时' . ($m % 60) . '分';
}

/* ---------- 分页 ---------- */
function pagination_html(string $base, int $page, int $totalPages): string
{
    if ($totalPages <= 1) return '';
    $html = '<div class="pagination">';
    $html .= '<a class="page-btn" ' . ($page <= 1 ? 'disabled' : 'href="' . e($base . 'page=' . ($page - 1)) . '"') . '><i class="ic ic-arrow-l"></i></a>';
    $from = max(1, $page - 2);
    $to = min($totalPages, $page + 2);
    if ($from > 1) {
        $html .= '<a class="page-btn" href="' . e($base . 'page=1') . '">1</a>';
        if ($from > 2) $html .= '<span class="page-btn" disabled>…</span>';
    }
    for ($i = $from; $i <= $to; $i++) {
        $html .= '<a class="page-btn ' . ($i === $page ? 'active' : '') . '" href="' . e($base . 'page=' . $i) . '">' . $i . '</a>';
    }
    if ($to < $totalPages) {
        if ($to < $totalPages - 1) $html .= '<span class="page-btn" disabled>…</span>';
        $html .= '<a class="page-btn" href="' . e($base . 'page=' . $totalPages) . '">' . $totalPages . '</a>';
    }
    $html .= '<a class="page-btn" ' . ($page >= $totalPages ? 'disabled' : 'href="' . e($base . 'page=' . ($page + 1)) . '"') . '><i class="ic ic-arrow-r"></i></a>';
    $html .= '</div>';
    return $html;
}

/* ---------- 邮件 ---------- */
function send_mail(string $to, string $subject, string $html): array
{
    $res = smtp_send($to, $subject, $html);
    try {
        db_x("INSERT INTO mail_log (email,subject,status,error,created_at) VALUES (?,?,?,?,NOW())", [
            $to, mb_substr($subject, 0, 190), $res[0] ? 1 : 0, mb_substr((string)$res[1], 0, 250),
        ]);
    } catch (Throwable $t) { /* 日志失败不影响业务 */ }
    return $res;
}

/** 邮件外壳（内联样式，兼容邮件客户端） */
function mail_wrap(string $title, string $inner): string
{
    $site = site_name();
    return '<!DOCTYPE html><html><body style="margin:0;padding:0;background:#0d1117;font-family:\'PingFang SC\',\'Microsoft YaHei\',Arial,sans-serif;">
<div style="max-width:560px;margin:0 auto;padding:32px 16px;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#141b2d;border-radius:18px;overflow:hidden;border:1px solid rgba(255,255,255,.08);">
<tr><td style="background:linear-gradient(135deg,#e50914,#ff4550);padding:26px 32px;">
<div style="font-size:22px;font-weight:bold;color:#ffffff;letter-spacing:1px;">' . e($site) . '<span style="font-size:12px;font-weight:normal;opacity:.85;margin-left:10px;">JAY VIDEO</span></div>
</td></tr>
<tr><td style="padding:34px 36px;">
<h2 style="margin:0 0 6px;color:#f0f3fa;font-size:19px;">' . e($title) . '</h2>
<div style="width:38px;height:3px;background:#e50914;border-radius:3px;margin:12px 0 22px;"></div>
' . $inner . '
</td></tr>
<tr><td style="padding:18px 36px;background:#0f1524;border-top:1px solid rgba(255,255,255,.06);">
<p style="margin:0;color:#5d6a80;font-size:12px;line-height:1.8;">这是一封由系统自动发送的邮件，请勿直接回复。<br>' . e($site) . ' · 让好电影触手可及</p>
</td></tr></table>
<p style="text-align:center;color:#3a4356;font-size:11px;margin-top:14px;">© ' . date('Y') . ' ' . e($site) . '</p>
</div></body></html>';
}

/** 注册验证码邮件 */
function mail_verify_code(string $code): string
{
    $digits = '';
    for ($i = 0; $i < strlen($code); $i++) {
        $digits .= '<td style="width:46px;height:56px;background:linear-gradient(160deg,#1d2740,#161d30);border:1px solid rgba(255,255,255,.12);border-radius:10px;color:#ffffff;font-size:26px;font-weight:700;text-align:center;vertical-align:middle;">' . $code[$i] . '</td>';
    }
    $inner = '<p style="color:#93a0b4;font-size:14px;margin:0 0 22px;">您好，感谢注册 <b style="color:#f0f3fa">' . e(site_name()) . '</b>！您本次注册的邮箱验证码为：</p>
<table role="presentation" cellpadding="0" cellspacing="0" style="border-spacing:8px 0;margin:0 auto 22px;"><tr>' . $digits . '</tr></table>
<p style="color:#93a0b4;font-size:13px;margin:0 0 8px;">验证码 10 分钟内有效，请尽快完成注册。</p>
<p style="color:#5d6a80;font-size:12.5px;margin:0;">如非本人操作，请忽略本邮件，您的账户不会被创建。</p>';
    return mail_wrap('邮箱验证码', $inner);
}

/** 封禁通知邮件 */
function mail_ban_notify(array $user): string
{
    $inner = '<p style="color:#93a0b4;font-size:14px;margin:0 0 18px;">尊敬的 <b style="color:#f0f3fa">' . e($user['username']) . '</b>，您的账号已被封禁，详情如下：</p>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#0f1524;border:1px solid rgba(255,255,255,.08);border-radius:12px;">
<tr><td style="padding:14px 18px;color:#5d6a80;font-size:13px;width:86px;">封禁原因</td><td style="padding:14px 18px;color:#ff7b81;font-size:14px;font-weight:600;">' . e($user['ban_reason'] ?: '违反社区规则') . '</td></tr>
<tr><td style="padding:14px 18px;color:#5d6a80;font-size:13px;border-top:1px solid rgba(255,255,255,.06);">封禁时间</td><td style="padding:14px 18px;color:#f0f3fa;font-size:14px;">' . e($user['ban_start'] ?: '立即生效') . '</td></tr>
<tr><td style="padding:14px 18px;color:#5d6a80;font-size:13px;border-top:1px solid rgba(255,255,255,.06);">解除时间</td><td style="padding:14px 18px;color:#4ade80;font-size:14px;">' . e($user['ban_end'] ?: '无限期') . '</td></tr>
</table>
<p style="color:#5d6a80;font-size:12.5px;margin:18px 0 0;">封禁期间您将无法登录与观看，如有疑问请联系管理员申诉。</p>';
    return mail_wrap('账号封禁通知', $inner);
}

/* ---------- 验证码 ---------- */
function verify_code_create(string $email): array
{
    // 60 秒内不可重复发送
    $last = db_one("SELECT * FROM verify_codes WHERE email=? ORDER BY id DESC LIMIT 1", [$email]);
    if ($last && strtotime($last['created_at']) > time() - 60) {
        return [false, '发送过于频繁，请稍后再试'];
    }
    $code = strval(random_int(100000, 999999));
    db_x("DELETE FROM verify_codes WHERE email=?", [$email]);
    db_x("INSERT INTO verify_codes (email,code,purpose,used,expires_at,created_at) VALUES (?,?,?,0,DATE_ADD(NOW(),INTERVAL 10 MINUTE),NOW())",
        [$email, $code, 'register']);
    $res = send_mail($email, '【' . site_name() . '】注册验证码：' . $code, mail_verify_code($code));
    if (!$res[0]) return [false, '邮件发送失败：' . $res[1]];
    return [true, '验证码已发送至邮箱，10分钟内有效'];
}

function verify_code_check(string $email, string $code): bool
{
    $row = db_one("SELECT * FROM verify_codes WHERE email=? AND code=? AND used=0 AND expires_at>NOW() ORDER BY id DESC LIMIT 1", [$email, $code]);
    if (!$row) return false;
    db_x("UPDATE verify_codes SET used=1 WHERE id=?", [$row['id']]);
    return true;
}

/* ---------- 收藏 / 历史 ---------- */
function fav_exists(int $uid, int $tmdbId, string $type): bool
{
    return db_val("SELECT id FROM favorites WHERE user_id=? AND tmdb_id=? AND media_type=?", [$uid, $tmdbId, $type]) !== null;
}

function history_latest(int $uid, int $tmdbId, string $type)
{
    return db_one("SELECT * FROM watch_history WHERE user_id=? AND tmdb_id=? AND media_type=? ORDER BY updated_at DESC LIMIT 1", [$uid, $tmdbId, $type]);
}

/* ---------- 导航激活 ---------- */
function nav_active(string $key): string
{
    $cur = basename($_SERVER['SCRIPT_NAME'] ?? '');
    $map = [
        'index'    => ['index.php'],
        'movie'    => ['category.php'],
        'tv'       => ['category.php'],
        'variety'  => ['category.php'],
        'anime'    => ['category.php'],
        'feedback' => ['feedback.php', 'feedback_view.php'],
    ];
    if (!isset($map[$key])) return '';
    if (!in_array($cur, $map[$key], true)) return '';
    if ($key !== 'index' && $cur === 'category.php') {
        return ($_GET['type'] ?? '') === $key ? 'active' : '';
    }
    if ($key === 'index') return $cur === 'index.php' ? 'active' : '';
    return 'active';
}

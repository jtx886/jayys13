<?php
/**
 * Jay影视 - 引导文件（所有页面入口统一加载）
 */
if (!defined('JAY_BOOTSTRAP')) {
    define('JAY_BOOTSTRAP', true);
    define('ROOT', dirname(__DIR__));

    /* 未安装则跳转安装向导 */
    if (!is_file(__DIR__ . '/config.php')) {
        $base = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'])), '/');
        // 允许在 /admin 子目录等位置触发跳转
        if (basename($base) === 'admin') { $base = rtrim(dirname($base), '/'); }
        header('Location: ' . $base . '/install.php');
        exit;
    }

    /* 统一时区：须在加载 db.php 前设置，数据库会话时区将与之对齐 */
    date_default_timezone_set('Asia/Shanghai');

    require_once __DIR__ . '/config.php';
    require_once __DIR__ . '/db.php';
    require_once __DIR__ . '/functions.php';
    require_once __DIR__ . '/tmdb.php';
    require_once __DIR__ . '/player.php';
    require_once __DIR__ . '/smtp.php';

    if (session_status() === PHP_SESSION_NONE) {
        session_name('JAYSESSID');
        session_start();
    }

    /* 登录用户处于封禁期：强制下线 */
    $u = current_user();
    if ($u !== null && user_banned($u)) {
        $_SESSION['banned_msg'] = '您的账号已被封禁（' . ($u['ban_reason'] ?: '违反社区规则')
            . '），解除时间：' . ($u['ban_end'] ?: '无限期');
        unset($_SESSION['uid']);
        if (basename($_SERVER['SCRIPT_NAME']) !== 'login.php') {
            redirect(u('login.php'));
        }
    }
}

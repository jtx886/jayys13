<?php
/**
 * Jay影视 - 安装向导
 * 首次访问：填写数据库信息 -> 自动建表 -> 生成配置 -> 销毁安装入口
 * 兼容 PHP 7.4 - 8.x / MySQL 5.6+（InfinityFree 等）
 */
error_reporting(E_ALL);
ini_set('display_errors', '0');

/* ---------- 已安装则直接回首页 ---------- */
if (is_file(__DIR__ . '/includes/config.php')) {
    header('Location: index.php');
    exit;
}

session_name('JAYSESSID');
@session_start();

/* ---------- 工具 ---------- */
function h($s) { return htmlspecialchars((string)$s, ENT_QUOTES, 'UTF-8'); }
function base_url_detect(): string
{
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https')
        || (($_SERVER['SERVER_PORT'] ?? '') == '443');
    $scheme = $https ? 'https' : 'http';
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    $dir = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '/')), '/');
    return $scheme . '://' . $host . $dir . '/';
}

$phpOk     = version_compare(PHP_VERSION, '7.4.0', '>=');
$pdoOk     = extension_loaded('pdo_mysql');
$curlOk    = function_exists('curl_init');
$mbOk      = extension_loaded('mbstring');
$gdOk      = extension_loaded('gd');
$writable  = is_writable(__DIR__) && is_writable(__DIR__ . '/includes');

/* ---------- 建表 SQL ---------- */
$TABLES = <<<'SQL'
CREATE TABLE IF NOT EXISTS `users` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `email` varchar(190) NOT NULL,
  `username` varchar(60) NOT NULL,
  `password` varchar(255) NOT NULL,
  `avatar` varchar(255) DEFAULT '',
  `role` varchar(20) DEFAULT 'user',
  `status` tinyint(4) DEFAULT 1,
  `ban_reason` varchar(255) DEFAULT '',
  `ban_start` datetime DEFAULT NULL,
  `ban_end` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT current_timestamp(),
  `last_login` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email` (`email`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `cache` (
  `cache_key` varchar(190) NOT NULL,
  `cache_value` longtext DEFAULT NULL,
  `expires_at` datetime DEFAULT NULL,
  PRIMARY KEY (`cache_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `favorites` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL,
  `tmdb_id` int(10) unsigned NOT NULL,
  `media_type` varchar(10) NOT NULL,
  `title` varchar(255) DEFAULT '',
  `poster` varchar(500) DEFAULT '',
  `created_at` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fav` (`user_id`,`tmdb_id`,`media_type`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `watch_history` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL,
  `tmdb_id` int(10) unsigned NOT NULL,
  `media_type` varchar(10) NOT NULL,
  `title` varchar(255) DEFAULT '',
  `poster` varchar(500) DEFAULT '',
  `season` int(11) DEFAULT 1,
  `episode` int(11) DEFAULT 1,
  `episode_name` varchar(255) DEFAULT '',
  `position_seconds` int(11) DEFAULT 0,
  `created_at` datetime DEFAULT current_timestamp(),
  `updated_at` datetime DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hist` (`user_id`,`tmdb_id`,`media_type`,`season`,`episode`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `feedbacks` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(10) unsigned NOT NULL,
  `title` varchar(150) NOT NULL,
  `content` text DEFAULT NULL,
  `is_public` tinyint(4) DEFAULT 1,
  `likes` int(11) DEFAULT 0,
  `created_at` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `feedback_replies` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `feedback_id` int(10) unsigned NOT NULL,
  `user_id` int(10) unsigned NOT NULL,
  `content` text DEFAULT NULL,
  `created_at` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_fb` (`feedback_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `feedback_likes` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `feedback_id` int(10) unsigned NOT NULL,
  `user_id` int(10) unsigned NOT NULL,
  `created_at` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_like` (`feedback_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `notices` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(150) DEFAULT '',
  `content` text DEFAULT NULL,
  `is_active` tinyint(4) DEFAULT 1,
  `created_at` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `play_sources` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `api_url` varchar(255) NOT NULL,
  `is_default` tinyint(4) DEFAULT 0,
  `status` tinyint(4) DEFAULT 1,
  `sort` int(11) DEFAULT 0,
  `created_at` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `mail_log` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `email` varchar(190) DEFAULT NULL,
  `subject` varchar(190) DEFAULT '',
  `status` tinyint(4) DEFAULT 0,
  `error` varchar(250) DEFAULT '',
  `created_at` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `settings` (
  `skey` varchar(60) NOT NULL,
  `svalue` text DEFAULT NULL,
  PRIMARY KEY (`skey`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `verify_codes` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `email` varchar(190) NOT NULL,
  `code` varchar(10) NOT NULL,
  `purpose` varchar(20) DEFAULT 'register',
  `used` tinyint(4) DEFAULT 0,
  `expires_at` datetime NOT NULL,
  `created_at` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
SQL;

/* ---------- 处理提交 ---------- */
$errors = [];
$done = false;
$doneAdmin = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $dbHost = trim($_POST['db_host'] ?? '127.0.0.1');
    $dbPort = trim($_POST['db_port'] ?? '3306');
    $dbName = trim($_POST['db_name'] ?? '');
    $dbUser = trim($_POST['db_user'] ?? '');
    $dbPass = (string)($_POST['db_pass'] ?? '');
    $tmdbKey = trim($_POST['tmdb_key'] ?? '');
    $adminUser = trim($_POST['admin_user'] ?? '');
    $adminPass = (string)($_POST['admin_pass'] ?? '');

    if ($dbName === '' || $dbUser === '') $errors[] = '请填写数据库名和数据库用户名';
    if ($adminUser === '' || $adminPass === '') $errors[] = '请填写管理员用户名和密码';
    if ($tmdbKey !== '' && !preg_match('/^[a-f0-9]{32}$/i', $tmdbKey)) $errors[] = 'TMDB API Key 格式不正确（32位十六进制）';

    if (!$errors) {
        try {
            $pdo = new PDO(
                "mysql:host={$dbHost};port={$dbPort};dbname={$dbName};charset=utf8mb4",
                $dbUser, $dbPass,
                [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_TIMEOUT => 10]
            );
        } catch (Throwable $e) {
            $errors[] = '数据库连接失败：' . $e->getMessage();
        }
    }

    if (!$errors) {
        try {
            /* 建表 */
            $pdo->exec("SET NAMES utf8mb4");
            foreach (array_filter(array_map('trim', explode(';', $TABLES))) as $ddl) {
                if ($ddl !== '') $pdo->exec($ddl);
            }

            /* 默认管理员 */
            $hash = password_hash($adminPass, PASSWORD_DEFAULT);
            $stmt = $pdo->prepare("INSERT INTO users (email,username,password,role,created_at)
                                   VALUES (?,?,?,'admin',NOW())");
            $stmt->execute(['admin@jay.local', $adminUser, $hash]);
            $doneAdmin = $adminUser;

            /* 默认播放源 */
            $pdo->exec("INSERT INTO play_sources (name,api_url,is_default,status,sort) VALUES
                        ('雁北飞资源','https://api.yyzy-tv.vip/inc/apijson.php',1,1,0)");

            /* 默认设置 */
            $ins = $pdo->prepare("INSERT INTO settings (skey,svalue) VALUES (?,?)
                                  ON DUPLICATE KEY UPDATE svalue=VALUES(svalue)");
            $ins->execute(['site_name', 'Jay影视']);
            $ins->execute(['theme_color', '#e50914']);
            $ins->execute(['tmdb_api_key', $tmdbKey]);

            /* 生成配置文件 */
            $appKey = bin2hex(random_bytes(16));
            $cfg = "<?php\n"
                 . "// Jay影视 - 自动生成的配置文件（" . date('Y-m-d H:i:s') . "）\n"
                 . "define('DB_HOST', " . var_export($dbHost, true) . ");\n"
                 . "define('DB_PORT', " . var_export($dbPort, true) . ");\n"
                 . "define('DB_NAME', " . var_export($dbName, true) . ");\n"
                 . "define('DB_USER', " . var_export($dbUser, true) . ");\n"
                 . "define('DB_PASS', " . var_export($dbPass, true) . ");\n"
                 . "define('BASE_URL', " . var_export(base_url_detect(), true) . ");\n"
                 . "define('APP_KEY', " . var_export($appKey, true) . ");\n"
                 . "\n// SMTP 邮件（163）\n"
                 . "define('SMTP_HOST', 'smtp.163.com');\n"
                 . "define('SMTP_PORT', 465);\n"
                 . "define('SMTP_USER', 'jtxnb886@163.com');\n"
                 . "define('SMTP_PASS', 'FLLRDtadYAfGXp9Y');\n"
                 . "define('SMTP_FROM', 'jtxnb886@163.com');\n"
                 . "define('SMTP_FROM_NAME', 'Jay影视');\n";

            if (@file_put_contents(__DIR__ . '/includes/config.php', $cfg) === false) {
                throw new RuntimeException('无法写入 includes/config.php，请检查目录权限');
            }
            @file_put_contents(__DIR__ . '/install.lock', date('Y-m-d H:i:s'));
            $done = true;

            /* 销毁安装入口 */
            @unlink(__FILE__);
        } catch (Throwable $e) {
            $errors[] = '安装失败：' . $e->getMessage();
        }
    }
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>安装向导 - Jay影视</title>
<style>
:root{--bg:#0d0f14;--panel:#161922;--panel2:#1c2029;--line:#2a2f3c;--text:#e8eaf0;--text2:#9aa0b4;--text3:#5f6678;--primary:#e50914;--ok:#2ecc71;--err:#e74c3c}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;background:var(--bg);color:var(--text);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px;line-height:1.6;
background-image:radial-gradient(ellipse 60% 40% at 50% -10%,rgba(229,9,20,.15),transparent),radial-gradient(ellipse 40% 30% at 90% 110%,rgba(229,9,20,.06),transparent)}
.wrap{width:100%;max-width:560px;animation:up .5s ease}
@keyframes up{from{opacity:0;transform:translateY(18px)}to{opacity:1;transform:none}}
.logo{display:flex;align-items:center;gap:12px;justify-content:center;margin-bottom:28px}
.logo-mark{width:44px;height:44px;border-radius:12px;background:linear-gradient(135deg,var(--primary),#ff5a45);display:flex;align-items:center;justify-content:center;position:relative}
.logo-mark::after{content:"";width:0;height:0;border-left:14px solid #fff;border-top:9px solid transparent;border-bottom:9px solid transparent;margin-left:4px}
.logo h1{font-size:26px;font-weight:700}
.logo span{color:var(--primary)}
.card{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:32px;box-shadow:0 18px 50px rgba(0,0,0,.5)}
.card h2{font-size:17px;margin-bottom:6px}
.card .desc{color:var(--text2);font-size:13px;margin-bottom:22px}
.step-tag{display:inline-block;background:var(--primary-15,rgba(229,9,20,.15));color:#ff6b62;border:1px solid rgba(229,9,20,.3);font-size:12px;padding:2px 10px;border-radius:99px;margin-bottom:10px}
.env{margin-bottom:22px}
.env-row{display:flex;justify-content:space-between;align-items:center;padding:9px 12px;background:var(--panel2);border-radius:8px;margin-bottom:8px;font-size:13px}
.env-row .st{display:inline-flex;align-items:center;gap:6px;font-size:12px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--ok);box-shadow:0 0 8px var(--ok)}
.dot.no{background:var(--err);box-shadow:0 0 8px var(--err)}
.env-row .st.no{color:var(--err)}
.fgroup{margin-bottom:16px}
.fgroup label{display:block;font-size:13px;color:var(--text2);margin-bottom:6px}
.fgroup input{width:100%;background:var(--panel2);border:1px solid var(--line);border-radius:8px;padding:11px 14px;color:var(--text);font-size:14px;transition:border-color .2s,box-shadow .2s;outline:none}
.fgroup input:focus{border-color:var(--primary);box-shadow:0 0 0 3px rgba(229,9,20,.15)}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:14px}
.hint{font-size:12px;color:var(--text3);margin-top:5px}
.errbox{background:rgba(231,76,60,.1);border:1px solid rgba(231,76,60,.35);color:#ff8f85;border-radius:8px;padding:11px 14px;font-size:13px;margin-bottom:18px;white-space:pre-line;animation:shake .35s}
@keyframes shake{0%,100%{transform:none}25%{transform:translateX(-5px)}75%{transform:translateX(5px)}}
.btn{width:100%;padding:13px;border:none;border-radius:10px;background:linear-gradient(135deg,var(--primary),#ff4d3d);color:#fff;font-size:15px;font-weight:600;cursor:pointer;transition:transform .2s,box-shadow .2s,opacity .2s}
.btn:hover{transform:translateY(-2px);box-shadow:0 10px 26px rgba(229,9,20,.4)}
.btn:active{transform:none}
.okbox{text-align:center;padding:20px 0}
.okicon{width:72px;height:72px;border-radius:50%;background:rgba(46,204,113,.12);border:2px solid var(--ok);margin:0 auto 18px;display:flex;align-items:center;justify-content:center;animation:pop .5s cubic-bezier(.34,1.56,.64,1)}
@keyframes pop{from{transform:scale(0);opacity:0}to{transform:scale(1);opacity:1}}
.okicon::before,.okicon::after{content:"";position:absolute;width:6px;height:32px;background:var(--ok);border-radius:3px}
.okicon::before{transform:rotate(45deg) translate(-6px,-6px)}
.okicon::after{transform:rotate(-45deg) translate(6px,6px)}
.okbox h2{font-size:20px;margin-bottom:8px}
.okbox p{color:var(--text2);font-size:14px;margin-bottom:22px}
.gobtn{display:inline-block;padding:12px 36px;border-radius:10px;background:linear-gradient(135deg,var(--primary),#ff4d3d);color:#fff;text-decoration:none;font-weight:600;transition:transform .2s,box-shadow .2s}
.gobtn:hover{transform:translateY(-2px);box-shadow:0 10px 26px rgba(229,9,20,.4)}
.foot{text-align:center;color:var(--text3);font-size:12px;margin-top:22px}
@media(max-width:520px){.card{padding:24px 20px}.grid2{grid-template-columns:1fr}}
</style>
</head>
<body>
<div class="wrap">
  <div class="logo">
    <div class="logo-mark"></div>
    <h1>Jay<span>影视</span></h1>
  </div>

  <div class="card">
    <?php if ($done): ?>
    <div class="okbox">
      <div class="okicon"></div>
      <h2>安装完成</h2>
      <p>数据库表已创建，配置文件已生成，安装入口已自动销毁。<br>管理员账号：<b><?= h($doneAdmin) ?></b></p>
      <a class="gobtn" href="index.php">进入首页</a>
    </div>
    <?php else: ?>
    <span class="step-tag">STEP 1 / 1 · 环境检测 &amp; 数据库配置</span>
    <h2>安装向导</h2>
    <p class="desc">适配 InfinityFree / PHP <?= PHP_VERSION ?> · 首次安装请填写数据库信息</p>

    <?php if ($errors): ?>
    <div class="errbox"><?= h(implode("\n", $errors)) ?></div>
    <?php endif; ?>

    <div class="env">
      <div class="env-row"><span>PHP 版本 ≥ 7.4（当前 <?= PHP_VERSION ?>）</span><span class="st <?= $phpOk ? '' : 'no' ?>"><i class="dot <?= $phpOk ? '' : 'no' ?>"></i><?= $phpOk ? '通过' : '不满足' ?></span></div>
      <div class="env-row"><span>PDO MySQL 扩展</span><span class="st <?= $pdoOk ? '' : 'no' ?>"><i class="dot <?= $pdoOk ? '' : 'no' ?>"></i><?= $pdoOk ? '通过' : '不满足' ?></span></div>
      <div class="env-row"><span>cURL 扩展（TMDB / 播放源）</span><span class="st <?= $curlOk ? '' : 'no' ?>"><i class="dot <?= $curlOk ? '' : 'no' ?>"></i><?= $curlOk ? '通过' : '缺失' ?></span></div>
      <div class="env-row"><span>mbstring 扩展</span><span class="st <?= $mbOk ? '' : 'no' ?>"><i class="dot <?= $mbOk ? '' : 'no' ?>"></i><?= $mbOk ? '通过' : '缺失' ?></span></div>
      <div class="env-row"><span>GD 扩展（头像处理）</span><span class="st <?= $gdOk ? '' : 'no' ?>"><i class="dot <?= $gdOk ? '' : 'no' ?>"></i><?= $gdOk ? '通过' : '缺失' ?></span></div>
      <div class="env-row"><span>目录可写（config 生成）</span><span class="st <?= $writable ? '' : 'no' ?>"><i class="dot <?= $writable ? '' : 'no' ?>"></i><?= $writable ? '通过' : '不可写' ?></span></div>
    </div>

    <form method="post" autocomplete="off">
      <div class="grid2">
        <div class="fgroup">
          <label>数据库主机</label>
          <input name="db_host" value="<?= h($_POST['db_host'] ?? '127.0.0.1') ?>" required>
        </div>
        <div class="fgroup">
          <label>数据库端口</label>
          <input name="db_port" value="<?= h($_POST['db_port'] ?? '3306') ?>" required>
        </div>
      </div>
      <div class="fgroup">
        <label>数据库名</label>
        <input name="db_name" value="<?= h($_POST['db_name'] ?? '') ?>" placeholder="如 if0_12345678_jay" required>
        <p class="hint">InfinityFree 需先在控制面板创建数据库</p>
      </div>
      <div class="grid2">
        <div class="fgroup">
          <label>数据库用户名</label>
          <input name="db_user" value="<?= h($_POST['db_user'] ?? '') ?>" required>
        </div>
        <div class="fgroup">
          <label>数据库密码</label>
          <input type="password" name="db_pass" value="<?= h($_POST['db_pass'] ?? '') ?>">
        </div>
      </div>
      <div class="fgroup">
        <label>TMDB API Key（v3）</label>
        <input name="tmdb_key" value="<?= h($_POST['tmdb_key'] ?? '') ?>" placeholder="32位十六进制 Key，可留空后到后台填写">
        <p class="hint">用于拉取影视元数据，可在网站设置中随时修改</p>
      </div>
      <div class="grid2">
        <div class="fgroup">
          <label>管理员用户名</label>
          <input name="admin_user" value="<?= h($_POST['admin_user'] ?? '杰同学') ?>" required>
        </div>
        <div class="fgroup">
          <label>管理员密码</label>
          <input name="admin_pass" value="<?= h($_POST['admin_pass'] ?? '101113') ?>" required>
        </div>
      </div>
      <button class="btn" type="submit">开始安装</button>
    </form>
    <?php endif; ?>
  </div>
  <p class="foot">Jay影视 · 安装完成后本文件将自动删除 · © <?= date('Y') ?></p>
</div>
</body>
</html>

<?php
/**
 * Jay影视 - SMTP 邮件发送（原生 Socket SSL，163邮箱）
 * 返回 [bool 是否成功, string 错误信息]
 */
function smtp_send(string $to, string $subject, string $htmlBody): array
{
    $host = SMTP_HOST;
    $port = (int)SMTP_PORT;
    $user = SMTP_USER;
    $pass = SMTP_PASS;
    $from = SMTP_FROM;
    $fromName = SMTP_FROM_NAME;

    $errno = 0;
    $errstr = '';
    $context = stream_context_create(['ssl' => ['verify_peer' => false, 'verify_peer_name' => false, 'allow_self_signed' => true]]);
    $sock = @stream_socket_client('ssl://' . $host . ':' . $port, $errno, $errstr, 18, STREAM_CLIENT_CONNECT, $context);
    if (!$sock) {
        return [false, '连接SMTP服务器失败(' . $errno . ') ' . $errstr];
    }
    stream_set_timeout($sock, 18);

    $read = function () use ($sock) {
        $data = '';
        while (($line = fgets($sock, 512)) !== false) {
            $data .= $line;
            if (isset($line[3]) && $line[3] === ' ') break; // 多行应答结束
        }
        return $data;
    };
    $cmd = function ($c, $expect) use ($sock, $read) {
        fwrite($sock, $c . "\r\n");
        $r = $read();
        return [$r !== false && substr($r, 0, 3) === (string)$expect, $r];
    };

    $greet = $read();
    if (substr($greet, 0, 3) !== '220') {
        fclose($sock);
        return [false, 'SMTP服务器无响应: ' . trim($greet)];
    }

    list($ok, $r) = $cmd('EHLO jayvideo', 250);
    if (!$ok) { fclose($sock); return [false, 'EHLO失败: ' . trim((string)$r)]; }

    list($ok, $r) = $cmd('AUTH LOGIN', 334);
    if (!$ok) { fclose($sock); return [false, '请求认证失败: ' . trim((string)$r)]; }

    list($ok, $r) = $cmd(base64_encode($user), 334);
    if (!$ok) { fclose($sock); return [false, '用户名错误: ' . trim((string)$r)]; }

    list($ok, $r) = $cmd(base64_encode($pass), 235);
    if (!$ok) { fclose($sock); return [false, '密码或授权码错误: ' . trim((string)$r)]; }

    list($ok, $r) = $cmd('MAIL FROM:<' . $from . '>', 250);
    if (!$ok) { fclose($sock); return [false, '设置发件人失败: ' . trim((string)$r)]; }

    list($ok, $r) = $cmd('RCPT TO:<' . $to . '>', 250);
    if (!$ok) { fclose($sock); return [false, '设置收件人失败: ' . trim((string)$r)]; }

    list($ok, $r) = $cmd('DATA', 354);
    if (!$ok) { fclose($sock); return [false, '进入数据模式失败: ' . trim((string)$r)]; }

    $encSubject = '=?UTF-8?B?' . base64_encode($subject) . '?=';
    $encFromName = '=?UTF-8?B?' . base64_encode($fromName) . '?=';
    $headers = [
        'From: ' . $encFromName . ' <' . $from . '>',
        'To: <' . $to . '>',
        'Subject: ' . $encSubject,
        'Date: ' . date('r'),
        'Message-ID: <' . md5(uniqid('', true)) . '@jayvideo>',
        'MIME-Version: 1.0',
        'Content-Type: text/html; charset="UTF-8"',
        'Content-Transfer-Encoding: base64',
    ];
    $body = implode("\r\n", $headers) . "\r\n\r\n" . chunk_split(base64_encode($htmlBody)) . "\r\n.";
    fwrite($sock, $body . "\r\n");
    $r = $read();
    if (substr($r, 0, 3) !== '250') {
        fclose($sock);
        return [false, '邮件被拒收: ' . trim((string)$r)];
    }

    fwrite($sock, "QUIT\r\n");
    fclose($sock);
    return [true, ''];
}

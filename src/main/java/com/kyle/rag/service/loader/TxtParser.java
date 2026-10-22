package com.kyle.rag.service.loader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
/**
 * TXT 文本解析器：优先按 UTF-8 解码，替换字符比例过高时降级到 GBK；去除 BOM 并统一换行符。
 * 空内容和疑似二进制文件返回失败结果，正常文本作为单页内容交给下游处理。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/22 00:41
 */
@Component
@Slf4j
public class TxtParser  implements DocumentParser {

    /** UTF-8 解码后若替换字符（U+FFFD）占比超过阈值，则视为疑似非 UTF-8，尝试 GBK */
    private static final double UTF8_DECODE_FAIL_THRESHOLD = 0.01;

    /** 非可打印控制字符占比阈值——超过即判定为二进制文件 */
    private static final double BINARY_THRESHOLD = 0.05;

    @Override
    public String supportedType() {
        return "TXT";
    }

    /**
     * 读取整个文本流并尝试解码、清理格式和检查二进制内容；TXT 不分页，成功时整体作为一页。
     * 遵守接口契约：异常不外抛，统一封装为失败结果。
     */
    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try {
            // 一次读取并保留原始字节，切换编码时可重新解码；输入流由调用方负责关闭
            byte[] bytes = inputStream.readAllBytes();

            // 1. 先尝试 UTF-8，乱码率高则降级到 GBK（覆盖国内常见的 ANSI/GBK 文件）
            Charset charset = StandardCharsets.UTF_8;
            String text = new String(bytes, charset);
            // String 解码遇到非法字节会插入 U+FFFD，不会直接抛异常，因此用替换字符占比估计解码质量
            long replacementCount = text.chars().filter(c -> c == 0xFFFD).count();
            if (replacementCount > text.length() * UTF8_DECODE_FAIL_THRESHOLD) {
                charset = Charset.forName("GBK");
                // 必须从原始字节重新解码，已被替换成 U+FFFD 的内容无法从 text 中恢复
                text = new String(bytes, charset);
                log.info("[TXT解析] 文件={} 非 UTF-8，降级到 GBK 解码", fileName);
            }

            // 2. 只移除文件开头的 BOM（U+FEFF）编码标记，避免正文首字符混入不可见字符
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }

            // 3. 先处理 Windows 的 CRLF，再处理单独的 CR，统一为 LF，避免一个换行被拆成两个
            text = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");

            if (text.isBlank()) {
                return ParseResult.failure("文本文件内容为空");
            }

            // 4. 二进制文件保护：非可打印控制字符占比过高，判定为非文本
            if (isLikelyBinary(text)) {
                return ParseResult.failure("文件包含大量非文本字符，疑似二进制文件");
            }

            log.info("[TXT解析] 文件={}，编码={}，字符数={}", fileName, charset.name(), text.length());

            // TXT 没有物理页码，整篇作为第 1 页；更细的文本分块交给下游处理
            return ParseResult.builder()
                    .success(true)
                    .pages(List.of(ParseResult.PageContent.builder()
                            .pageNum(1)
                            .text(text.strip())  // 只去掉全文首尾空白，保留正文内部的换行与缩进
                            .build()))
                    .totalPages(1)
                    .build();

        } catch (Exception e) {
            log.error("[TXT解析] 文件={}，解析失败：{}", fileName, e.getMessage(), e);
            return ParseResult.failure("TXT 解析失败：" + e.getMessage());
        }
    }

    /** 简单启发式：非换行/制表符的控制字符占比超过阈值，视为二进制文件 */
    private boolean isLikelyBinary(String text) {
        // 换行、制表符属于正常文本排版，只统计其余 ISO 控制字符（如 NUL）
        long nonPrintable = text.chars()
                .filter(c -> Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r')
                .count();
        // 按比例而非固定数量判断，适配不同文本长度；这里只做启发式过滤，不能精确识别文件格式
        return nonPrintable > text.length() * BINARY_THRESHOLD;
    }
}

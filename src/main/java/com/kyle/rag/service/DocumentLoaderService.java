package com.kyle.rag.service;

import com.kyle.rag.service.loader.DocumentParser;
import com.kyle.rag.service.loader.ParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档解析的统一入口：根据原始文件名的扩展名选择解析器，返回统一的 ParseResult。
 * 各格式的文本提取由 DocumentParser 实现负责，后续切块和向量化由下游处理。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/22 01:26
 */
@Service
@Slf4j
public class DocumentLoaderService {

    /** 文件类型到解析器的注册表：构造时初始化，后续请求只查询，不修改。 */
    private final Map<String, DocumentParser> parsers;

    /**
     * Spring 自动收集容器中的所有 DocumentParser 实现，无须逐个手动注册。
     * 同一类型只能对应一个解析器；出现重复类型时 toMap 会抛异常，使启动失败并暴露配置冲突。
     */
    public DocumentLoaderService(List<DocumentParser> parserList) {
        // 统一使用大写类型作为 key，与 supportedType() 的接口约定保持一致
        this.parsers = parserList.stream()
                .collect(Collectors.toMap(
                        p -> p.supportedType().toUpperCase(),
                        Function.identity()
                ));
        log.info("已加载文档解析器：{}", parsers.keySet());
    }

    /**
     * 解析文档，根据文件类型自动选择解析器。
     * 输入流由调用方负责关闭（如在调用层使用 try-with-resources），本服务不主动关闭。
     * 解析器按 DocumentParser 契约将解析异常封装为失败结果，调用方通过 success 判断是否继续处理。
     *
     * @param inputStream 文件输入流，需处于可读取状态
     * @param fileName    带扩展名的原始文件名（用于选择解析器和日志定位）
     * @return 解析器返回的结果；类型不受支持时返回 success=false 的失败结果
     */
    public ParseResult load(InputStream inputStream, String fileName) {
        String fileType = detectFileType(fileName).toUpperCase();
        DocumentParser parser = parsers.get(fileType);

        // 没有对应解析器时直接返回失败，此分支不读取输入流
        if (parser == null) {
            log.warn("[文档加载] 不支持的文件类型：{}，文件：{}", fileType, fileName);
            return ParseResult.failure("不支持的文件类型：" + fileType +
                    "，目前支持：PDF / DOCX / MD / TXT");
        }

        log.info("[文档加载] 开始解析：fileName={}，type={}", fileName, fileType);
        long start = System.currentTimeMillis();

        // 解析后的页码含义由格式决定：PDF 是物理页码，DOCX/MD 可按节编号，TXT 整篇为一页
        ParseResult result = parser.parse(inputStream, fileName);

        long elapsed = System.currentTimeMillis() - start;
        if (result.isSuccess()) {
            log.info("[文档加载] 解析完成：fileName={}，页数={}，耗时={}ms",
                    fileName, result.getTotalPages(), elapsed);
        } else {
            log.warn("[文档加载] 解析失败：fileName={}，原因={}",
                    fileName, result.getErrorMsg());
        }

        return result;
    }

    /**
     * 按最后一个点号后的扩展名识别类型；只依据文件名，不检查文件实际内容。
     * .md 和 .markdown 统一映射为 MD；其余后缀转成大写后交给注册表判断是否受支持。
     * 文件名为 null 或不含点号时返回 UNKNOWN。
     */
    private String detectFileType(String fileName) {
        if (fileName == null) return "UNKNOWN";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) return "UNKNOWN";
        String ext = fileName.substring(dotIndex + 1).toLowerCase();
        return switch (ext) {
            case "pdf"  -> "PDF";
            case "docx" -> "DOCX";
            case "md", "markdown" -> "MD";
            case "txt"  -> "TXT";
            default -> ext.toUpperCase();
        };
    }

}

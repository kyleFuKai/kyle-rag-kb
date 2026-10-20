package com.kyle.rag.service.loader;

import java.io.InputStream;
/**
 * 文档解析器 SPI：每种文件类型一个实现（PdfDocumentParser、DocxDocumentParser...），
 * 通过 supportedType() 注册到统一入口，按 kb_document.file_type 分发。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/20 22:01
 */
public interface DocumentParser {

    /** 支持的文件类型（大写），须与 kb_document.file_type 取值一致，如 "PDF"、"DOCX" */
    String supportedType();

    /**
     * 解析文件；不向外抛异常，失败（含文件损坏）统一封装进 ParseResult.errorMsg。
     * inputStream 由调用方负责关闭；fileName 仅用于日志与错误信息定位。
     */
    ParseResult parse(InputStream inputStream, String fileName);
}

package com.kyle.rag.service.splitter;

import lombok.Builder;
import lombok.Data;
/**
 * 单次文档分块使用的参数：控制块大小、重叠长度以及是否允许优先使用结构信息。
 * 长度统一以字符数表示；Token 数由分块结果单独估算。
 * {@code @Builder.Default} 保证通过 builder 创建对象、且未指定某个字段时，使用该字段的默认值。
 * 本类只保存参数，取值合法性需要由调用层或分块实现检查。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/22 23:21
 */
@Data
@Builder
public class ChunkConfig {

    /**
     * 每块正文的最大字符数，默认 512，必须大于 0。
     * 章节或段落超过此上限时，分块实现应继续拆分；512 字符不等于 512 Token。
     */
    @Builder.Default
    private int chunkSize = 512;

    /**
     * 相邻块保留的重叠字符数，默认 64，用于缓解切分边界处的上下文丢失。
     * 应满足 {@code 0 <= chunkOverlap < chunkSize}；固定滑动窗口的步长为二者之差。
     * 结构感知策略中如何安排重叠、是否跨章节重叠，由具体实现决定。
     */
    @Builder.Default
    private int chunkOverlap = 64;

    /**
     * 是否允许优先使用结构感知分块，默认 true。
     * 供上层结合解析结果中的有效 sectionTitle 选择策略；缺少结构信息时使用滑动窗口。
     * 设置为 true 只是允许使用该策略，标题识别和策略选择由解析器及调用层负责。
     */
    @Builder.Default
    private boolean structureAware = true;

    /**
     * 创建一个独立的默认配置实例：块大小 512 字符、重叠 64 字符、允许结构感知分块。
     * 此方法使用类中声明的默认值；application.yml 中的配置需由上层读取后传入。
     */
    public static ChunkConfig defaultConfig() {
        return ChunkConfig.builder().build();
    }
}

package com.youkeda.exercise.claw.feature.file;

/**
 * 文件存储接口
 *
 * <p>抽象文件存储层，支持本地文件系统和未来替换为对象存储（OSS/S3 等）。
 * {@link FileService} 通过此接口操作文件，不依赖具体实现。
 */
public interface FileStorage {

    /**
     * 保存文件
     *
     * @param userId    用户标识
     * @param content   文件字节
     * @param extension 文件扩展名（不含点，如 "md"）
     * @return 存储文件名（如 "a1b2c3d4_操作系统学习笔记.md"）
     * @throws IllegalArgumentException 文件类型不受支持或超出大小限制
     */
    String save(String userId, byte[] content, String extension);

    /**
     * 读取文件字节
     *
     * @param userId     用户标识
     * @param storedName 存储文件名
     * @return 文件字节，不存在返回 null
     */
    byte[] read(String userId, String storedName);

    /**
     * 删除文件
     *
     * @param userId     用户标识
     * @param storedName 存储文件名
     * @return true 表示已删除
     */
    boolean delete(String userId, String storedName);

    /**
     * 判断扩展名是否在白名单中
     *
     * @param extension 扩展名（不含点）
     * @return true 表示允许
     */
    boolean isAllowedExtension(String extension);

    /**
     * 校验文件大小是否在限制内
     *
     * @param content 文件字节
     * @return true 表示允许
     */
    boolean isWithinSizeLimit(byte[] content);
}

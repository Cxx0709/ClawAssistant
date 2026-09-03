package com.youkeda.exercise.claw.feature.scout;

/** 向量计算工具类（收敛多份重复实现）。 */
public final class VectorUtils {

    private VectorUtils() {
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * <p>无效输入（null / 长度不等 / 零范数）统一返回 0f，
     * 消除此前多份实现 0f 与 -1f 语义不一致的问题。
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0f;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0 ? 0f : (float) (dot / denominator);
    }
}

package io.sqlgraph.connector.embed;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ONNX Runtime 을 실행 시점에 받은 jar 에서 불러 리플렉션으로만 부른다.
 * (컴파일 의존성이 없어 폐쇄망 빌드에 영향이 없다.)
 */
public final class OrtRunner implements PooledRunner {

    private final URLClassLoader loader;
    private final Object env;
    private final Object options;
    private final Object session;
    private final boolean needsTokenTypes;
    private final Method createTensor;
    private final Method sessionRun;
    private final Method resultGet;
    private final Method valueGetValue;

    private OrtRunner(URLClassLoader loader, Object env, Object options, Object session, boolean needsTokenTypes,
                      Method createTensor, Method sessionRun, Method resultGet, Method valueGetValue) {
        this.loader = loader;
        this.env = env;
        this.options = options;
        this.session = session;
        this.needsTokenTypes = needsTokenTypes;
        this.createTensor = createTensor;
        this.sessionRun = sessionRun;
        this.resultGet = resultGet;
        this.valueGetValue = valueGetValue;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static OrtRunner open(Path runtimeJar, Path model, int threads) throws IOException {
        if (runtimeJar == null || !Files.isRegularFile(runtimeJar)) {
            throw new IOException("ONNX Runtime jar 를 찾을 수 없습니다: " + runtimeJar);
        }
        if (model == null || !Files.isRegularFile(model)) {
            throw new IOException("ONNX 모델 파일을 찾을 수 없습니다: " + model);
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{runtimeJar.toUri().toURL()},
                OrtRunner.class.getClassLoader());
        Object options = null;
        try {
            Class<?> envC = Class.forName("ai.onnxruntime.OrtEnvironment", true, loader);
            Class<?> sessionC = Class.forName("ai.onnxruntime.OrtSession", true, loader);
            Class<?> optsC = Class.forName("ai.onnxruntime.OrtSession$SessionOptions", true, loader);
            Class<?> levelC = Class.forName("ai.onnxruntime.OrtSession$SessionOptions$OptLevel", true, loader);
            Class<?> tensorC = Class.forName("ai.onnxruntime.OnnxTensor", true, loader);
            Class<?> resultC = Class.forName("ai.onnxruntime.OrtSession$Result", true, loader);
            Class<?> valueC = Class.forName("ai.onnxruntime.OnnxValue", true, loader);

            Object env = envC.getMethod("getEnvironment").invoke(null);
            options = optsC.getConstructor().newInstance();
            if (threads > 0) {
                optsC.getMethod("setIntraOpNumThreads", int.class).invoke(options, threads);
            }
            Object all = Enum.valueOf((Class) levelC, "ALL_OPT");
            optsC.getMethod("setOptimizationLevel", levelC).invoke(options, all);
            Object session = envC.getMethod("createSession", String.class, optsC)
                    .invoke(env, model.toAbsolutePath().toString(), options);
            Set<String> inputs = (Set<String>) sessionC.getMethod("getInputNames").invoke(session);
            return new OrtRunner(loader, env, options, session, inputs.contains("token_type_ids"),
                    tensorC.getMethod("createTensor", envC, Object.class),
                    sessionC.getMethod("run", Map.class),
                    resultC.getMethod("get", int.class),
                    valueC.getMethod("getValue"));
        } catch (Throwable t) {
            closeQuietly(options);
            try {
                loader.close();
            } catch (IOException ignored) {
                // 무시
            }
            Throwable cause = unwrap(t);
            if (cause instanceof IOException io) throw io;
            throw new IOException("ONNX Runtime 을 열 수 없습니다 (" + runtimeJar + ", " + model + "): " + cause, cause);
        }
    }

    @Override
    public float[][] run(long[][] inputIds, long[][] attentionMask) throws Exception {
        int b = inputIds.length;
        if (b == 0) return new float[0][];
        List<AutoCloseable> tensors = new ArrayList<>();
        try {
            Map<String, Object> in = new HashMap<>();
            in.put("input_ids", tensor(inputIds, tensors));
            in.put("attention_mask", tensor(attentionMask, tensors));
            if (needsTokenTypes) {
                long[][] zeros = new long[b][inputIds[0].length];
                in.put("token_type_ids", tensor(zeros, tensors));
            }
            Object result = invoke(sessionRun, session, in);
            try (AutoCloseable ignored = (AutoCloseable) result) {
                Object value = invoke(resultGet, result, 0);
                float[][][] hidden = (float[][][]) invoke(valueGetValue, value);
                return pool(hidden, attentionMask);
            }
        } finally {
            for (AutoCloseable t : tensors) closeQuietly(t);
        }
    }

    static float[][] pool(float[][][] hidden, long[][] mask) {
        float[][] out = new float[hidden.length][];
        for (int i = 0; i < hidden.length; i++) {
            int d = hidden[i].length == 0 ? 0 : hidden[i][0].length;
            float[] v = new float[d];
            int count = 0;
            for (int t = 0; t < hidden[i].length; t++) {
                if (mask[i][t] == 0) continue;
                count++;
                float[] h = hidden[i][t];
                for (int k = 0; k < d; k++) v[k] += h[k];
            }
            if (count > 0) for (int k = 0; k < d; k++) v[k] /= count;
            out[i] = v;
        }
        return out;
    }

    private Object tensor(long[][] data, List<AutoCloseable> sink) throws Exception {
        Object t = invoke(createTensor, null, env, data);
        sink.add((AutoCloseable) t);
        return t;
    }

    private static Object invoke(Method m, Object target, Object... args) throws Exception {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) throw ex;
            if (c instanceof Error err) throw err;
            throw e;
        }
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
        return t;
    }

    private static void closeQuietly(Object o) {
        if (o instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 무시
            }
        }
    }

    @Override
    public void close() throws IOException {
        closeQuietly(session);
        closeQuietly(options);
        loader.close();
    }
}

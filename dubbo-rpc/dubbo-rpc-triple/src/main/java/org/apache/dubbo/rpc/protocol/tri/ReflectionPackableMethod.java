/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.rpc.protocol.tri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.serialize.MultipleSerialization;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.Constants;
import org.apache.dubbo.remoting.utils.UrlUtils;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.PackableMethod;

import com.google.protobuf.Message;

import java.util.stream.Stream;

import static org.apache.dubbo.common.constants.CommonConstants.$ECHO;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOBUF_MESSAGE_CLASS_NAME;
import static org.apache.dubbo.rpc.protocol.tri.TripleProtocol.METHOD_ATTR_PACK;

public class ReflectionPackableMethod implements PackableMethod {

    private static final String GRPC_ASYNC_RETURN_CLASS = "com.google.common.util.concurrent.ListenableFuture";
    private static final String TRI_ASYNC_RETURN_CLASS = "java.util.concurrent.CompletableFuture";
    private static final String REACTOR_RETURN_CLASS = "reactor.core.publisher.Mono";
    private static final String RX_RETURN_CLASS = "io.reactivex.Single";
    private static final String GRPC_STREAM_CLASS = "io.grpc.stub.StreamObserver";
    private static final Pack PB_PACK = o -> ((Message) o).toByteArray();

    private final Pack requestPack;
    private final Pack responsePack;
    private final UnPack requestUnpack;
    private final UnPack responseUnpack;
    private final Pack originalPack;
    private final UnPack originalUnpack;

    public ReflectionPackableMethod(MethodDescriptor method, URL url, String serializeName, boolean isServer) {
        Class<?>[] actualTypes;
        Class<?>[] actualRequestTypes;
        Class<?> actualResponseType;
        switch (method.getRpcType()) {
            case CLIENT_STREAM:
            case BI_STREAM:
                actualRequestTypes = new Class<?>[]{
                    (Class<?>) ((ParameterizedType) method.getMethod()
                        .getGenericReturnType()).getActualTypeArguments()[0]};
                actualResponseType = (Class<?>) ((ParameterizedType) method.getMethod()
                    .getGenericParameterTypes()[0]).getActualTypeArguments()[0];
                break;
            case SERVER_STREAM:
                actualRequestTypes = method.getMethod().getParameterTypes();
                actualResponseType = (Class<?>) ((ParameterizedType) method.getMethod()
                    .getGenericParameterTypes()[1]).getActualTypeArguments()[0];
                break;
            case UNARY:
                actualRequestTypes = method.getParameterClasses();
                actualResponseType = (Class<?>) method.getReturnTypes()[0];
                break;
            default:
                throw new IllegalStateException("Can not reach here");
        }

        boolean singleArgument = method.getRpcType() != MethodDescriptor.RpcType.UNARY;
        if (!needWrap(method, actualRequestTypes, actualResponseType)) {
            requestPack = new PbArrayPacker(singleArgument);
            originalPack = new PbArrayPacker(singleArgument);
            responsePack = PB_PACK;
            requestUnpack = new PbUnpack<>(actualRequestTypes[0]);
            responseUnpack = new PbUnpack<>(actualResponseType);
            originalUnpack = new PbUnpack<>(actualResponseType);

        } else {
            final MultipleSerialization serialization = url.getOrDefaultFrameworkModel()
                .getExtensionLoader(MultipleSerialization.class)
                .getExtension(url.getParameter(Constants.MULTI_SERIALIZATION_KEY,
                    CommonConstants.DEFAULT_KEY));

            this.requestPack = new WrapRequestPack(serialization, url, serializeName, actualRequestTypes,
                singleArgument);
            this.responsePack = new WrapResponsePack(serialization, url, actualResponseType);
            this.requestUnpack = new WrapRequestUnpack(serialization, url, actualRequestTypes);
            this.responseUnpack = new WrapResponseUnpack(serialization, url, actualResponseType);


            singleArgument = isServer ? isServer : singleArgument;

            actualTypes = isServer ? new Class[]{actualResponseType} : actualRequestTypes;
            this.originalPack = new OriginalPack(serialization, url, actualTypes, singleArgument);
            actualTypes = isServer ? actualRequestTypes : new Class[]{actualResponseType};
            this.originalUnpack = new OriginalUnpack(serialization, url, actualTypes);
        }
    }

    public static ReflectionPackableMethod init(MethodDescriptor methodDescriptor, URL url, boolean isServer) {
        final String serializeName = UrlUtils.serializationOrDefault(url);
        Object stored = methodDescriptor.getAttribute(METHOD_ATTR_PACK);
        if (stored != null) {
            return (ReflectionPackableMethod) stored;
        }
        ReflectionPackableMethod reflectionPackableMethod = new ReflectionPackableMethod(
            methodDescriptor, url, serializeName, isServer);
        methodDescriptor.addAttribute(METHOD_ATTR_PACK, reflectionPackableMethod);
        return reflectionPackableMethod;
    }

    static boolean isStreamType(Class<?> type) {
        return StreamObserver.class.isAssignableFrom(type) || GRPC_STREAM_CLASS.equalsIgnoreCase(
            type.getName());
    }

    /**
     * Determine if the request and response instance should be wrapped in Protobuf wrapper object
     *
     * @return true if the request and response object is not generated by protobuf
     */
    static boolean needWrap(MethodDescriptor methodDescriptor, Class<?>[] parameterClasses,
                            Class<?> returnClass) {
        String methodName = methodDescriptor.getMethodName();
        // generic call must be wrapped
        if (CommonConstants.$INVOKE.equals(methodName) || CommonConstants.$INVOKE_ASYNC.equals(
            methodName)) {
            return true;
        }
        // echo must be wrapped
        if ($ECHO.equals(methodName)) {
            return true;
        }
        boolean returnClassProtobuf = isProtobufClass(returnClass);
        // Response foo()
        if (parameterClasses.length == 0) {
            return !returnClassProtobuf;
        }
        int protobufParameterCount = 0;
        int javaParameterCount = 0;
        int streamParameterCount = 0;
        boolean secondParameterStream = false;
        // count normal and protobuf param
        for (int i = 0; i < parameterClasses.length; i++) {
            Class<?> parameterClass = parameterClasses[i];
            if (isProtobufClass(parameterClass)) {
                protobufParameterCount++;
            } else {
                if (isStreamType(parameterClass)) {
                    if (i == 1) {
                        secondParameterStream = true;
                    }
                    streamParameterCount++;
                } else {
                    javaParameterCount++;
                }
            }
        }
        // more than one stream param
        if (streamParameterCount > 1) {
            throw new IllegalStateException(
                "method params error: more than one Stream params. method=" + methodName);
        }
        // protobuf only support one param
        if (protobufParameterCount >= 2) {
            throw new IllegalStateException(
                "method params error: more than one protobuf params. method=" + methodName);
        }
        // server stream support one normal param and one stream param
        if (streamParameterCount == 1) {
            if (javaParameterCount + protobufParameterCount > 1) {
                throw new IllegalStateException(
                    "method params error: server stream does not support more than one normal param."
                        + " method=" + methodName);
            }
            // server stream: void foo(Request, StreamObserver<Response>)
            if (!secondParameterStream) {
                throw new IllegalStateException(
                    "method params error: server stream's second param must be StreamObserver."
                        + " method=" + methodName);
            }
        }
        if (methodDescriptor.getRpcType() != MethodDescriptor.RpcType.UNARY) {
            if (MethodDescriptor.RpcType.SERVER_STREAM == methodDescriptor.getRpcType()) {
                if (!secondParameterStream) {
                    throw new IllegalStateException(
                        "method params error:server stream's second param must be StreamObserver."
                            + " method=" + methodName);
                }
            }
            // param type must be consistent
            if (returnClassProtobuf) {
                if (javaParameterCount > 0) {
                    throw new IllegalStateException(
                        "method params error: both normal and protobuf param found. method="
                            + methodName);
                }
            } else {
                if (protobufParameterCount > 0) {
                    throw new IllegalStateException("method params error method=" + methodName);
                }
            }
        } else {
            if (streamParameterCount > 0) {
                throw new IllegalStateException(
                    "method params error: unary method should not contain any StreamObserver."
                        + " method=" + methodName);
            }
            if (protobufParameterCount > 0 && returnClassProtobuf) {
                return false;
            }
            // handler reactor or rxjava only consider gen by proto
            if (isMono(returnClass) || isRx(returnClass)) {
                return false;
            }
            if (protobufParameterCount <= 0 && !returnClassProtobuf) {
                return true;
            }
            // handle grpc stub only consider gen by proto
            if (GRPC_ASYNC_RETURN_CLASS.equalsIgnoreCase(returnClass.getName())
                && protobufParameterCount == 1) {
                return false;
            }
            // handle dubbo generated method
            if (TRI_ASYNC_RETURN_CLASS.equalsIgnoreCase(returnClass.getName())) {
                Class<?> actualReturnClass = (Class<?>) ((ParameterizedType) methodDescriptor.getMethod()
                    .getGenericReturnType()).getActualTypeArguments()[0];
                boolean actualReturnClassProtobuf = isProtobufClass(actualReturnClass);
                if (actualReturnClassProtobuf && protobufParameterCount == 1) {
                    return false;
                }
                if (!actualReturnClassProtobuf && protobufParameterCount == 0) {
                    return true;
                }
            }
            // todo remove this in future
            boolean ignore = checkNeedIgnore(returnClass);
            if (ignore) {
                return protobufParameterCount != 1;
            }
            throw new IllegalStateException("method params error method=" + methodName);
        }
        // java param should be wrapped
        return javaParameterCount > 0;
    }

    /**
     * fixme will produce error on grpc. but is harmless so ignore now
     */
    static boolean checkNeedIgnore(Class<?> returnClass) {
        return Iterator.class.isAssignableFrom(returnClass);
    }

    static boolean isMono(Class<?> clz) {
        return REACTOR_RETURN_CLASS.equalsIgnoreCase(clz.getName());
    }

    static boolean isRx(Class<?> clz) {
        return RX_RETURN_CLASS.equalsIgnoreCase(clz.getName());
    }

    static boolean isProtobufClass(Class<?> clazz) {
        while (clazz != Object.class && clazz != null) {
            Class<?>[] interfaces = clazz.getInterfaces();
            if (interfaces.length > 0) {
                for (Class<?> clazzInterface : interfaces) {
                    if (PROTOBUF_MESSAGE_CLASS_NAME.equalsIgnoreCase(clazzInterface.getName())) {
                        return true;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static String convertHessianFromWrapper(String serializeType) {
        if (TripleConstant.HESSIAN4.equals(serializeType)) {
            return TripleConstant.HESSIAN2;
        }
        return serializeType;
    }

    @Override
    public Pack getRequestPack(String contentType) {
        if (isNotOriginalSerializeType(contentType)) {
            return requestPack;
        }
        ((OriginalPack) originalPack).serialize = getSerializeType(contentType);
        return originalPack;
    }

    @Override
    public Pack getResponsePack(String contentType) {
        if (isNotOriginalSerializeType(contentType)) {
            return responsePack;
        }
        ((OriginalPack) originalPack).serialize = getSerializeType(contentType);
        return originalPack;
    }

    @Override
    public UnPack getResponseUnpack(String contentType) {
        if (isNotOriginalSerializeType(contentType)) {
            return responseUnpack;
        }
        ((OriginalUnpack) originalUnpack).serialize = getSerializeType(contentType);
        return originalUnpack;
    }

    @Override
    public UnPack getRequestUnpack(String contentType) {
        if (isNotOriginalSerializeType(contentType)) {
            return requestUnpack;
        }
        ((OriginalUnpack) originalUnpack).serialize = getSerializeType(contentType);
        return originalUnpack;
    }

    private boolean isNotOriginalSerializeType(String contentType) {

        return TripleConstant.CONTENT_PROTO.contains(contentType);
    }

    private String getSerializeType(String contentType) {
        // contentType：application/grpc、application/grpc+proto ...
        String[] contentTypes = contentType.split("\\+");
        return contentTypes[1];
    }

    private static class WrapResponsePack implements Pack {

        private final MultipleSerialization multipleSerialization;
        private final URL url;

        private final Class<?> actualResponseType;
        String serialize;

        private WrapResponsePack(MultipleSerialization multipleSerialization, URL url,
                                 Class<?> actualResponseType) {
            this.multipleSerialization = multipleSerialization;
            this.url = url;
            this.actualResponseType = actualResponseType;
        }

        @Override
        public byte[] pack(Object obj) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            multipleSerialization.serialize(url, serialize, actualResponseType, obj, bos);
            return TripleCustomerProtocolWapper.TripleResponseWrapper.Builder.newBuilder()
                .setSerializeType(serialize)
                .setType(actualResponseType.getName())
                .setData(bos.toByteArray())
                .build()
                .toByteArray();
        }
    }

    private static class WrapResponseUnpack implements UnPack {

        private final MultipleSerialization serialization;
        private final URL url;
        private final Class<?> returnClass;


        private WrapResponseUnpack(MultipleSerialization serialization, URL url, Class<?> returnClass) {
            this.serialization = serialization;
            this.url = url;
            this.returnClass = returnClass;
        }

        @Override
        public Object unpack(byte[] data) throws IOException, ClassNotFoundException {
            TripleCustomerProtocolWapper.TripleResponseWrapper wrapper = TripleCustomerProtocolWapper.TripleResponseWrapper
                .parseFrom(data);
            final String serializeType = convertHessianFromWrapper(wrapper.getSerializeType());
            ByteArrayInputStream bais = new ByteArrayInputStream(wrapper.getData());
            return serialization.deserialize(url, serializeType, returnClass, bais);
        }
    }

    private static class WrapRequestPack implements Pack {

        private final String serialize;
        private final MultipleSerialization multipleSerialization;
        private final String[] argumentsType;
        private final URL url;
        private final boolean singleArgument;

        private WrapRequestPack(MultipleSerialization multipleSerialization,
                                URL url,
                                String serialize,
                                Class<?>[] actualRequestTypes,
                                boolean singleArgument) {
            this.url = url;
            this.serialize = serialize;
            this.multipleSerialization = multipleSerialization;
            this.argumentsType = Stream.of(actualRequestTypes).map(Class::getName).toArray(String[]::new);
            this.singleArgument = singleArgument;
        }

        @Override
        public byte[] pack(Object obj) throws IOException {
            Object[] arguments;
            if (singleArgument) {
                arguments = new Object[]{obj};
            } else {
                arguments = (Object[]) obj;
            }
            final TripleCustomerProtocolWapper.TripleRequestWrapper.Builder builder = TripleCustomerProtocolWapper.TripleRequestWrapper.Builder.newBuilder();
            builder.setSerializeType(serialize);
            for (String type : argumentsType) {
                builder.addArgTypes(type);
            }
            for (Object argument : arguments) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                multipleSerialization.serialize(url, serialize, argument.getClass(), argument, bos);
                builder.addArgs(bos.toByteArray());
            }
            return builder.build().toByteArray();
        }

    }

    private static class PbArrayPacker implements Pack {

        private final boolean singleArgument;

        private PbArrayPacker(boolean singleArgument) {
            this.singleArgument = singleArgument;
        }

        @Override
        public byte[] pack(Object obj) throws IOException {
            if (!singleArgument) {
                obj = ((Object[]) obj)[0];
            }
            return PB_PACK.pack(obj);
        }
    }

    private class WrapRequestUnpack implements UnPack {

        private final MultipleSerialization serialization;
        private final URL url;

        private final Class<?>[] actualRequestTypes;

        private WrapRequestUnpack(MultipleSerialization serialization, URL url, Class<?>[] actualRequestTypes) {
            this.serialization = serialization;
            this.url = url;
            this.actualRequestTypes = actualRequestTypes;
        }

        @Override
        public Object unpack(byte[] data) throws IOException, ClassNotFoundException {
            TripleCustomerProtocolWapper.TripleRequestWrapper wrapper = TripleCustomerProtocolWapper.TripleRequestWrapper.parseFrom(
                data);
            Object[] ret = new Object[wrapper.getArgs().size()];
            ((WrapResponsePack) responsePack).serialize = wrapper.getSerializeType();
            for (int i = 0; i < wrapper.getArgs().size(); i++) {
                ByteArrayInputStream bais = new ByteArrayInputStream(
                    wrapper.getArgs().get(i));
                ret[i] = serialization.deserialize(url, wrapper.getSerializeType(),
                    actualRequestTypes[i],
                    bais);
            }
            return ret;
        }


    }

    private static class OriginalPack implements Pack {

        private final MultipleSerialization multipleSerialization;
        private final URL url;
        private final boolean singleArgument;
        private final Class<?>[] actualTypes;

        String serialize;

        private OriginalPack(MultipleSerialization multipleSerialization,
                             URL url,
                             Class<?>[] actualTypes,
                             boolean singleArgument) {
            this.url = url;
            this.multipleSerialization = multipleSerialization;
            this.singleArgument = singleArgument;
            this.actualTypes = actualTypes;
        }

        @Override
        public byte[] pack(Object obj) throws IOException {
            Object[] arguments = singleArgument ? new Object[]{obj} : (Object[]) obj;
            List<byte[]> argumentByteList = new ArrayList<>(arguments.length);

            for (int i = 0; i < arguments.length; i++) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                Class<?> clazz = arguments[i] == null ? actualTypes[i] : arguments[i].getClass();
                multipleSerialization.serialize(url, serialize, clazz, arguments[i], bos);
                argumentByteList.add(bos.toByteArray());
            }

            return argumentByteList.size() == 1 ? argumentByteList.get(0) : buildArgumentBytes(argumentByteList);
        }

        private byte[] buildArgumentBytes(List<byte[]> argumentByteList) {
            int totalSize = 0;
            int argTag = TripleCustomerProtocolWapper.makeTag(2, 2);

            totalSize += TripleCustomerProtocolWapper.varIntComputeLength(argTag) * argumentByteList.size();
            for (byte[] arg : argumentByteList) {
                totalSize += arg.length + TripleCustomerProtocolWapper.varIntComputeLength(arg.length);
            }

            ByteBuffer byteBuffer = ByteBuffer.allocate(totalSize);
            byte[] argTagBytes = TripleCustomerProtocolWapper.varIntEncode(argTag);
            for (byte[] arg : argumentByteList) {
                byteBuffer
                    .put(argTagBytes)
                    .put(TripleCustomerProtocolWapper.varIntEncode(arg.length))
                    .put(arg);
            }

            return byteBuffer.array();
        }

    }

    private static class OriginalUnpack implements UnPack {
        private final MultipleSerialization serialization;
        private final URL url;
        private final Class<?>[] actualTypes;
        private final boolean singleArgument;

        String serialize;

        private OriginalUnpack(MultipleSerialization serialization, URL url, Class<?>[] actualTypes) {
            this.serialization = serialization;
            this.url = url;
            this.actualTypes = actualTypes;
            this.singleArgument = actualTypes.length == 1 ;
        }

        @Override
        public Object unpack(byte[] data) throws IOException, ClassNotFoundException {
            List<byte[]> bytes;
            if (singleArgument) {
                List<byte[]> singleArgumentData = new ArrayList<>();
                singleArgumentData.add(data);
                bytes = singleArgumentData;
            }else{
                bytes = revertArgumentBytes(data);
            }

            Object[] ret = new Object[actualTypes.length];
            for (int i = 0; i < actualTypes.length; i++) {
                ByteArrayInputStream bais = new ByteArrayInputStream(bytes.get(i));
                ret[i] = serialization.deserialize(url, serialize, actualTypes[i], bais);
            }

            return singleArgument ? ret[0] : ret;
        }

        private List<byte[]> revertArgumentBytes(byte[] data) {
            ByteBuffer byteBuffer = ByteBuffer.wrap(data);
            List<byte[]> args = new ArrayList<>();
            while (byteBuffer.position() < byteBuffer.limit()) {
                int tag = TripleCustomerProtocolWapper.readRawVarint32(byteBuffer);
                int fieldNum = TripleCustomerProtocolWapper.extractFieldNumFromTag(tag);
                if (fieldNum == 2) {
                    int argLength = TripleCustomerProtocolWapper.readRawVarint32(byteBuffer);
                    byte[] argBytes = new byte[argLength];
                    byteBuffer.get(argBytes, 0, argLength);
                    args.add(argBytes);
                } else {
                    throw new RuntimeException("fieldNum should is 2 ");
                }
            }
            return args;
        }

    }

}

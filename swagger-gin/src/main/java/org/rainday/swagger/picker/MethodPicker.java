package org.rainday.swagger.picker;

import static org.rainday.swagger.picker.Reader.DEFAULT_DESCRIPTION;
import static org.rainday.swagger.picker.Reader.DEFAULT_MEDIA_TYPE_VALUE;
import static org.rainday.swagger.picker.Reader.components;
import static org.rainday.swagger.picker.Reader.openAPI;
import static org.rainday.swagger.picker.Reader.openApiTags;
import static org.rainday.swagger.picker.Reader.paths;
import static org.rainday.swagger.picker.Reader.read;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.ParameterProcessor;
import io.swagger.v3.core.util.PathUtils;
import io.swagger.v3.core.util.ReflectionUtils;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.callbacks.Callback;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.rainday.logging.Logger;
import org.rainday.logging.LoggerFactory;
import org.rainday.swagger.OpenAPIExtension;
import org.rainday.swagger.OpenAPIExtensions;
import org.rainday.swagger.OperationParser;
import org.rainday.swagger.ResolvedParameter;
import org.rainday.swagger.util.ReaderUtils;
import org.rainday.swagger.utils.StringUtils;
import org.rainday.ws.rs.annotations.Consumes;
import org.rainday.ws.rs.annotations.Path;
import org.rainday.ws.rs.annotations.Produces;

/**
 * Created by admin on 2021/1/13 15:56:24.
 */
public class MethodPicker {
    private static final String GET_METHOD = "get";
    private static final String POST_METHOD = "post";
    private static final String PUT_METHOD = "put";
    private static final String DELETE_METHOD = "delete";
    private static final String PATCH_METHOD = "patch";
    private static final String TRACE_METHOD = "trace";
    private static final String HEAD_METHOD = "head";
    private static final String OPTIONS_METHOD = "options";
    
    
    static Logger logger = LoggerFactory.getLogger(MethodPicker.class);
    
    public static void pick(Method method, OpenAPIDefinitionAttr openApiAttr) {
        
        String parentPath = openApiAttr.parentPath;
        
        String parentMethod = openApiAttr.parentMethod;
        boolean isSubresource = openApiAttr.isSubresource;
        RequestBody parentRequestBody = openApiAttr.parentRequestBody;
        ApiResponses parentResponses = openApiAttr.parentResponses;
        Set<String> parentTags = openApiAttr.parentTags;
        List<Parameter> parentParameters = openApiAttr.parentParameters;
        Set<Class<?>> scannedResources = openApiAttr.scannedResources;
        Consumes classConsumes = openApiAttr.classConsumes;
        Set<String> classTags = openApiAttr.classTags;
        
        Path apiPath = openApiAttr.getApiPath();
        
        Class<?> cls = method.getDeclaringClass();
        JavaType classType = TypeFactory.defaultInstance().constructType(cls);
        BeanDescription bd = Json.mapper().getSerializationConfig().introspect(classType);
        
        //hidden, overridden -> skip
        if (!isOperationHidden(method) && !isMethodOverridden(method, cls)) {
            
            Path methodPath = ReflectionUtils.getAnnotation(method, Path.class);
            String operationPath = ReaderUtils.getPath(apiPath, methodPath, parentPath, isSubresource);
            
            // skip if path is the same as parent, e.g. for @ApplicationPath annotated application
            // extending resource config.
            if (ignoreOperationPath(operationPath, parentPath) && !isSubresource) {
                return;
            }
            
            AnnotatedMethod annotatedMethod = bd.findMethod(method.getName(), method.getParameterTypes());
            Produces methodProduces = ReflectionUtils.getAnnotation(method, Produces.class);
            Consumes methodConsumes = ReflectionUtils.getAnnotation(method, Consumes.class);
            boolean methodDeprecated = ReflectionUtils.getAnnotation(method, Deprecated.class) != null;
            
            Map<String, String> regexMap = new LinkedHashMap<>();
            operationPath = PathUtils.parsePath(operationPath, regexMap);
            if (operationPath != null) {
                //always false, skip
                /*if (ReaderUtils.isIgnored(operationPath)) {
                    return;
                }*/
                
                final Class<?> subResource = getSubResourceWithJaxRsSubresourceLocatorSpecs(method);
                
                String httpMethod = ReaderUtils.extractOperationMethod(method, OpenAPIExtensions.chain());
                httpMethod = (httpMethod == null && isSubresource) ? parentMethod : httpMethod;
                
                if (StringUtils.isBlank(httpMethod) && subResource == null) {
                    return;
                } else if (StringUtils.isBlank(httpMethod) && subResource != null) {
                    Type returnType = method.getGenericReturnType();
                    if (annotatedMethod != null && annotatedMethod.getType() != null) {
                        returnType = annotatedMethod.getType();
                    }
                    
                    if (shouldIgnoreClass(returnType.getTypeName()) && !method.getGenericReturnType().equals(subResource)) {
                        return;
                    }
                }
                
                io.swagger.v3.oas.annotations.Operation apiOperation = ReflectionUtils
                        .getAnnotation(method, io.swagger.v3.oas.annotations.Operation.class);
                JsonView jsonViewAnnotation;
                JsonView jsonViewAnnotationForRequestBody;
                if (apiOperation != null && apiOperation.ignoreJsonView()) {
                    jsonViewAnnotation = null;
                    jsonViewAnnotationForRequestBody = null;
                } else {
                    jsonViewAnnotation = ReflectionUtils.getAnnotation(method, JsonView.class);
                    /* If one and only one exists, use the @JsonView annotation from the method parameter annotated
                       with @RequestBody. Otherwise fall back to the @JsonView annotation for the method itself. */
                    jsonViewAnnotationForRequestBody = (JsonView) Arrays.stream(ReflectionUtils.getParameterAnnotations(method))
                            .filter(arr ->
                                    Arrays.stream(arr)
                                            .anyMatch(annotation ->
                                                    annotation.annotationType()
                                                            .equals(io.swagger.v3.oas.annotations.parameters.RequestBody.class)
                                            )
                            ).flatMap(Arrays::stream)
                            .filter(annotation ->
                                    annotation.annotationType()
                                            .equals(JsonView.class)
                            ).reduce((a, b) -> null)
                            .orElse(jsonViewAnnotation);
                }
                
                Operation operation = parseMethod(
                        method,
                        openApiAttr.globalParameters,
                        methodProduces,
                        openApiAttr.classProduces,
                        methodConsumes,
                        openApiAttr.classConsumes,
                        null,
                        openApiAttr.classExternalDocumentation,
                        openApiAttr.classTags,
                        openApiAttr.classServers,
                        isSubresource,
                        parentRequestBody,
                        parentResponses,
                        jsonViewAnnotation,
                        openApiAttr.classResponses,
                        annotatedMethod);
                if (operation != null) {
                    if (openApiAttr.classDeprecated || methodDeprecated) {
                        operation.setDeprecated(true);
                    }
                    
                    List<Parameter> operationParameters = new ArrayList<>();
                    List<Parameter> formParameters = new ArrayList<>();
                    Annotation[][] paramAnnotations = ReflectionUtils.getParameterAnnotations(method);
                    if (annotatedMethod == null) { // annotatedMethod not null only when method with 0-2 parameters
                        Type[] genericParameterTypes = method.getGenericParameterTypes();
                        for (int i = 0; i < genericParameterTypes.length; i++) {
                            final Type type = TypeFactory.defaultInstance().constructType(genericParameterTypes[i], cls);
                            io.swagger.v3.oas.annotations.Parameter paramAnnotation = AnnotationsUtils
                                    .getAnnotation(io.swagger.v3.oas.annotations.Parameter.class, paramAnnotations[i]);
                            Type paramType = ParameterProcessor.getParameterType(paramAnnotation, true);
                            if (paramType == null) {
                                paramType = type;
                            } else {
                                if (!(paramType instanceof Class)) {
                                    paramType = type;
                                }
                            }
                            ResolvedParameter resolvedParameter = getParameters(paramType, Arrays.asList(paramAnnotations[i]), operation,
                                    openApiAttr.classConsumes, methodConsumes, jsonViewAnnotation);
                            operationParameters.addAll(resolvedParameter.parameters);
                            // collect params to use together as request Body
                            formParameters.addAll(resolvedParameter.formParameters);
                            if (resolvedParameter.requestBody != null) {
                                processRequestBody(
                                        resolvedParameter.requestBody,
                                        operation,
                                        methodConsumes,
                                        classConsumes,
                                        operationParameters,
                                        paramAnnotations[i],
                                        type,
                                        jsonViewAnnotationForRequestBody);
                            }
                        }
                    } else {
                        for (int i = 0; i < annotatedMethod.getParameterCount(); i++) {
                            AnnotatedParameter param = annotatedMethod.getParameter(i);
                            final Type type = TypeFactory.defaultInstance().constructType(param.getParameterType(), cls);
                            io.swagger.v3.oas.annotations.Parameter paramAnnotation = AnnotationsUtils
                                    .getAnnotation(io.swagger.v3.oas.annotations.Parameter.class, paramAnnotations[i]);
                            Type paramType = ParameterProcessor.getParameterType(paramAnnotation, true);
                            if (paramType == null) {
                                paramType = type;
                            } else {
                                if (!(paramType instanceof Class)) {
                                    paramType = type;
                                }
                            }
                            //-----------------------------------------------
                            AnnotatedType annotatedType = new AnnotatedType()
                                    .type(type)
                                    .resolveAsRef(true)
                                    .skipOverride(true);
                            
                            ResolvedSchema resolvedSchema = ModelConverters.getInstance().resolveAsResolvedSchema(annotatedType);
                            //-----------------------------------------------
                            
                            ResolvedParameter resolvedParameter = getParameters(paramType, Arrays.asList(paramAnnotations[i]), operation,
                                    classConsumes, methodConsumes, jsonViewAnnotation);
                            operationParameters.addAll(resolvedParameter.parameters);
                            // collect params to use together as request Body
                            formParameters.addAll(resolvedParameter.formParameters);
                            if (resolvedParameter.requestBody != null) {
                                processRequestBody(
                                        resolvedParameter.requestBody,
                                        operation,
                                        methodConsumes,
                                        classConsumes,
                                        operationParameters,
                                        paramAnnotations[i],
                                        type,
                                        jsonViewAnnotationForRequestBody);
                            }
                        }
                    }
                    // if we have form parameters, need to merge them into single schema and use as request body..
                    if (!formParameters.isEmpty()) {
                        Schema mergedSchema = new ObjectSchema();
                        for (Parameter formParam : formParameters) {
                            mergedSchema.addProperties(formParam.getName(), formParam.getSchema());
                            if (null != formParam.getRequired() && formParam.getRequired()) {
                                mergedSchema.addRequiredItem(formParam.getName());
                            }
                        }
                        Parameter merged = new Parameter().schema(mergedSchema);
                        processRequestBody(
                                merged,
                                operation,
                                methodConsumes,
                                classConsumes,
                                operationParameters,
                                new Annotation[0],
                                null,
                                jsonViewAnnotationForRequestBody);
                        
                    }
                    if (!operationParameters.isEmpty()) {
                        for (Parameter operationParameter : operationParameters) {
                            operation.addParametersItem(operationParameter);
                        }
                    }
                    
                    // if subresource, merge parent parameters
                    if (parentParameters != null) {
                        for (Parameter parentParameter : parentParameters) {
                            operation.addParametersItem(parentParameter);
                        }
                    }
                    
                    if (subResource != null && !scannedResources.contains(subResource)) {
                        scannedResources.add(subResource);
                        read(subResource, operationPath, httpMethod, true, operation.getRequestBody(), operation.getResponses(),
                                classTags,
                                operation.getParameters(), scannedResources);
                        // remove the sub resource so that it can visit it later in another path
                        // but we have a room for optimization in the future to reuse the scanned result
                        // by caching the scanned resources in the reader instance to avoid actual scanning
                        // the the resources again
                        scannedResources.remove(subResource);
                        // don't proceed with root resource operation, as it's handled by subresource
                        return;
                    }
                    
                    final Iterator<OpenAPIExtension> chain = OpenAPIExtensions.chain();
                    if (chain.hasNext()) {
                        final OpenAPIExtension extension = chain.next();
                        extension.decorateOperation(operation, method, chain);
                    }
                    
                    PathItem pathItemObject;
                    if (paths != null && paths.get(operationPath) != null) {
                        pathItemObject = openAPI.getPaths().get(operationPath);
                    } else {
                        pathItemObject = new PathItem();
                    }
                    
                    if (StringUtils.isBlank(httpMethod)) {
                        return;
                    }
                    setPathItemOperation(pathItemObject, httpMethod, operation);
                    
                    paths.addPathItem(operationPath, pathItemObject);
                    //merge class paths into openApi paths
                    if (openAPI.getPaths() != null) {
                        paths.putAll(openAPI.getPaths());
                    }
                    openAPI.setPaths(paths);
                }
            }
        }
    }
    
    protected static boolean isOperationHidden(Method method) {
        io.swagger.v3.oas.annotations.Operation apiOperation = ReflectionUtils
                .getAnnotation(method, io.swagger.v3.oas.annotations.Operation.class);
        if (apiOperation != null && apiOperation.hidden()) {
            return true;
        }
        Hidden hidden = method.getAnnotation(Hidden.class);
        if (hidden != null) {
            return true;
        }
        if (!Boolean.TRUE.equals(true) && apiOperation == null) {
            return true;
        }
        return false;
    }
    
    protected static boolean isMethodOverridden(Method method, Class<?> cls) {
        return ReflectionUtils.isOverriddenMethod(method, cls);
    }
    
    protected static boolean ignoreOperationPath(String path, String parentPath) {
        
        if (StringUtils.isBlank(path) && StringUtils.isBlank(parentPath)) {
            return true;
        } else if (StringUtils.isNotBlank(path) && StringUtils.isBlank(parentPath)) {
            return false;
        } else if (StringUtils.isBlank(path) && StringUtils.isNotBlank(parentPath)) {
            return false;
        }
        if (parentPath != null && !"".equals(parentPath) && !"/".equals(parentPath)) {
            if (!parentPath.startsWith("/")) {
                parentPath = "/" + parentPath;
            }
            if (parentPath.endsWith("/")) {
                parentPath = parentPath.substring(0, parentPath.length() - 1);
            }
        }
        if (path != null && !"".equals(path) && !"/".equals(path)) {
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
        }
        return path.equals(parentPath);
    }
    
    
    protected static Class<?> getSubResourceWithJaxRsSubresourceLocatorSpecs(Method method) {
        final Class<?> rawType = method.getReturnType();
        final Class<?> type;
        if (Class.class.equals(rawType)) {
            type = getClassArgument(method.getGenericReturnType());
            if (type == null) {
                return null;
            }
        } else {
            type = rawType;
        }
        
        if (method.getAnnotation(Path.class) != null) {
            if (ReaderUtils.extractOperationMethod(method, null) == null) {
                return type;
            }
        }
        return null;
    }
    
    
    private static Class<?> getClassArgument(Type cls) {
        if (cls instanceof ParameterizedType) {
            final ParameterizedType parameterized = (ParameterizedType) cls;
            final Type[] args = parameterized.getActualTypeArguments();
            if (args.length != 1) {
                logger.error("Unexpected class definition: {}", cls);
                return null;
            }
            final Type first = args[0];
            if (first instanceof Class) {
                return (Class<?>) first;
            } else {
                return null;
            }
        } else {
            logger.error("Unknown class definition: {}", cls);
            return null;
        }
    }
    
    
    private static boolean shouldIgnoreClass(String className) {
        if (StringUtils.isBlank(className)) {
            return true;
        }
        boolean ignore = false;
        String rawClassName = className;
        if (rawClassName.startsWith("[")) { // jackson JavaType
            rawClassName = className.replace("[simple type, class ", "");
            rawClassName = rawClassName.substring(0, rawClassName.length() - 1);
        }
        ignore = rawClassName.startsWith("javax.ws.rs.");
        ignore = ignore || rawClassName.startsWith("org.rainday.ws.rs.");
        ignore = ignore || rawClassName.equalsIgnoreCase("void");
        ignore = ignore || ModelConverters.getInstance().isRegisteredAsSkippedClass(rawClassName);
        return ignore;
    }
    
    public Operation parseMethod(
            Method method,
            List<Parameter> globalParameters,
            JsonView jsonViewAnnotation) {
        JavaType classType = TypeFactory.defaultInstance().constructType(method.getDeclaringClass());
        return parseMethod(
                classType.getClass(),
                method,
                globalParameters,
                null,
                null,
                null,
                null,
                new ArrayList<>(),
                Optional.empty(),
                new HashSet<>(),
                new ArrayList<>(),
                false,
                null,
                null,
                jsonViewAnnotation,
                null,
                null);
    }
    
    public Operation parseMethod(
            Method method,
            List<Parameter> globalParameters,
            Produces methodProduces,
            Produces classProduces,
            Consumes methodConsumes,
            Consumes classConsumes,
            List<SecurityRequirement> classSecurityRequirements,
            Optional<io.swagger.v3.oas.models.ExternalDocumentation> classExternalDocs,
            Set<String> classTags,
            List<io.swagger.v3.oas.models.servers.Server> classServers,
            boolean isSubresource,
            RequestBody parentRequestBody,
            ApiResponses parentResponses,
            JsonView jsonViewAnnotation,
            io.swagger.v3.oas.annotations.responses.ApiResponse[] classResponses) {
        JavaType classType = TypeFactory.defaultInstance().constructType(method.getDeclaringClass());
        return parseMethod(
                classType.getClass(),
                method,
                globalParameters,
                methodProduces,
                classProduces,
                methodConsumes,
                classConsumes,
                classSecurityRequirements,
                classExternalDocs,
                classTags,
                classServers,
                isSubresource,
                parentRequestBody,
                parentResponses,
                jsonViewAnnotation,
                classResponses,
                null);
    }
    
    public static Operation parseMethod(
            Method method,
            List<Parameter> globalParameters,
            Produces methodProduces,
            Produces classProduces,
            Consumes methodConsumes,
            Consumes classConsumes,
            List<SecurityRequirement> classSecurityRequirements,
            Optional<io.swagger.v3.oas.models.ExternalDocumentation> classExternalDocs,
            Set<String> classTags,
            List<io.swagger.v3.oas.models.servers.Server> classServers,
            boolean isSubresource,
            RequestBody parentRequestBody,
            ApiResponses parentResponses,
            JsonView jsonViewAnnotation,
            io.swagger.v3.oas.annotations.responses.ApiResponse[] classResponses,
            AnnotatedMethod annotatedMethod) {
        JavaType classType = TypeFactory.defaultInstance().constructType(method.getDeclaringClass());
        return parseMethod(
                classType.getClass(),
                method,
                globalParameters,
                methodProduces,
                classProduces,
                methodConsumes,
                classConsumes,
                classSecurityRequirements,
                classExternalDocs,
                classTags,
                classServers,
                isSubresource,
                parentRequestBody,
                parentResponses,
                jsonViewAnnotation,
                classResponses,
                annotatedMethod);
    }
    
    protected static Operation parseMethod(
            Class<?> cls,
            Method method,
            List<Parameter> globalParameters,
            Produces methodProduces,
            Produces classProduces,
            Consumes methodConsumes,
            Consumes classConsumes,
            List<SecurityRequirement> classSecurityRequirements,
            Optional<io.swagger.v3.oas.models.ExternalDocumentation> classExternalDocs,
            Set<String> classTags,
            List<io.swagger.v3.oas.models.servers.Server> classServers,
            boolean isSubresource,
            RequestBody parentRequestBody,
            ApiResponses parentResponses,
            JsonView jsonViewAnnotation,
            io.swagger.v3.oas.annotations.responses.ApiResponse[] classResponses,
            AnnotatedMethod annotatedMethod) {
        Operation operation = new Operation();
        
        io.swagger.v3.oas.annotations.Operation apiOperation = ReflectionUtils
                .getAnnotation(method, io.swagger.v3.oas.annotations.Operation.class);
        
        List<io.swagger.v3.oas.annotations.security.SecurityRequirement> apiSecurity = ReflectionUtils
                .getRepeatableAnnotations(method, io.swagger.v3.oas.annotations.security.SecurityRequirement.class);
        List<io.swagger.v3.oas.annotations.callbacks.Callback> apiCallbacks = ReflectionUtils
                .getRepeatableAnnotations(method, io.swagger.v3.oas.annotations.callbacks.Callback.class);
        List<Server> apiServers = ReflectionUtils.getRepeatableAnnotations(method, Server.class);
        List<io.swagger.v3.oas.annotations.tags.Tag> apiTags = ReflectionUtils
                .getRepeatableAnnotations(method, io.swagger.v3.oas.annotations.tags.Tag.class);
        List<io.swagger.v3.oas.annotations.Parameter> apiParameters = ReflectionUtils
                .getRepeatableAnnotations(method, io.swagger.v3.oas.annotations.Parameter.class);
        List<io.swagger.v3.oas.annotations.responses.ApiResponse> apiResponses = ReflectionUtils
                .getRepeatableAnnotations(method, io.swagger.v3.oas.annotations.responses.ApiResponse.class);
        io.swagger.v3.oas.annotations.parameters.RequestBody apiRequestBody =
                ReflectionUtils.getAnnotation(method, io.swagger.v3.oas.annotations.parameters.RequestBody.class);
        
        io.swagger.v3.oas.annotations.ExternalDocumentation apiExternalDocumentation = ReflectionUtils
                .getAnnotation(method, io.swagger.v3.oas.annotations.ExternalDocumentation.class);
        
        // callbacks
        Map<String, Callback> callbacks = new LinkedHashMap<>();
        
        if (apiCallbacks != null) {
            for (io.swagger.v3.oas.annotations.callbacks.Callback methodCallback : apiCallbacks) {
                Map<String, Callback> currentCallbacks = getCallbacks(methodCallback, methodProduces, classProduces, methodConsumes,
                        classConsumes, jsonViewAnnotation);
                callbacks.putAll(currentCallbacks);
            }
        }
        if (callbacks.size() > 0) {
            operation.setCallbacks(callbacks);
        }
        
        // todo  security
        //classSecurityRequirements.forEach(operation::addSecurityItem);
        if (apiSecurity != null) {
            //todo security 注释
           /* Optional<List<SecurityRequirement>> requirementsObject = SecurityParser.getSecurityRequirements(apiSecurity.toArray(new io.swagger.v3.oas.annotations.security.SecurityRequirement[apiSecurity.size()]));
            if (requirementsObject.isPresent()) {
                requirementsObject.get().stream()
                        .filter(r -> operation.getSecurity() == null || !operation.getSecurity().contains(r))
                        .forEach(operation::addSecurityItem);
            }*/
        }
        
        // servers
        if (classServers != null) {
            classServers.forEach(operation::addServersItem);
        }
        
        if (apiServers != null) {
            AnnotationsUtils.getServers(apiServers.toArray(new Server[apiServers.size()]))
                    .ifPresent(servers -> servers.forEach(operation::addServersItem));
        }
        
        // external docs
        AnnotationsUtils.getExternalDocumentation(apiExternalDocumentation).ifPresent(operation::setExternalDocs);
        
        // method tags
        if (apiTags != null) {
            apiTags.stream()
                    .filter(t -> operation.getTags() == null || (operation.getTags() != null && !operation.getTags().contains(t.name())))
                    .map(io.swagger.v3.oas.annotations.tags.Tag::name)
                    .forEach(operation::addTagsItem);
            AnnotationsUtils.getTags(apiTags.toArray(new io.swagger.v3.oas.annotations.tags.Tag[apiTags.size()]), true)
                    .ifPresent(tags -> openApiTags.addAll(tags));
        }
        
        // parameters
        if (globalParameters != null) {
            for (Parameter globalParameter : globalParameters) {
                operation.addParametersItem(globalParameter);
            }
        }
        if (apiParameters != null) {
            getParametersListFromAnnotation(
                    apiParameters.toArray(new io.swagger.v3.oas.annotations.Parameter[apiParameters.size()]),
                    classConsumes,
                    methodConsumes,
                    operation,
                    jsonViewAnnotation).ifPresent(p -> p.forEach(operation::addParametersItem));
        }
        
        // RequestBody in Method
        if (apiRequestBody != null && operation.getRequestBody() == null) {
            OperationParser.getRequestBody(apiRequestBody, classConsumes, methodConsumes, components, jsonViewAnnotation).ifPresent(
                    operation::setRequestBody);
        }
        
        // operation id
        if (StringUtils.isBlank(operation.getOperationId())) {
            operation.setOperationId(getOperationId(method.getName()));
        }
        
        // classResponses
        if (classResponses != null && classResponses.length > 0) {
            OperationParser.getApiResponses(
                    classResponses,
                    classProduces,
                    methodProduces,
                    components,
                    jsonViewAnnotation
            ).ifPresent(responses -> {
                if (operation.getResponses() == null) {
                    operation.setResponses(responses);
                } else {
                    responses.forEach(operation.getResponses()::addApiResponse);
                }
            });
        }
        
        if (apiOperation != null) {
            setOperationObjectFromApiOperationAnnotation(operation, apiOperation, methodProduces, classProduces, methodConsumes,
                    classConsumes, jsonViewAnnotation);
        }
        
        // apiResponses
        if (apiResponses != null && !apiResponses.isEmpty()) {
            OperationParser.getApiResponses(
                    apiResponses.toArray(new io.swagger.v3.oas.annotations.responses.ApiResponse[apiResponses.size()]),
                    classProduces,
                    methodProduces,
                    components,
                    jsonViewAnnotation
            ).ifPresent(responses -> {
                if (operation.getResponses() == null) {
                    operation.setResponses(responses);
                } else {
                    responses.forEach(operation.getResponses()::addApiResponse);
                }
            });
        }
        
        // class tags after tags defined as field of @Operation
        if (classTags != null) {
            classTags.stream()
                    .filter(t -> operation.getTags() == null || (operation.getTags() != null && !operation.getTags().contains(t)))
                    .forEach(operation::addTagsItem);
        }
        
        // external docs of class if not defined in annotation of method or as field of Operation annotation
        if (operation.getExternalDocs() == null) {
            classExternalDocs.ifPresent(operation::setExternalDocs);
        }
        
        // if subresource, merge parent requestBody
        if (isSubresource && parentRequestBody != null) {
            if (operation.getRequestBody() == null) {
                operation.requestBody(parentRequestBody);
            } else {
                Content content = operation.getRequestBody().getContent();
                if (content == null) {
                    content = parentRequestBody.getContent();
                    operation.getRequestBody().setContent(content);
                } else if (parentRequestBody.getContent() != null) {
                    for (String parentMediaType : parentRequestBody.getContent().keySet()) {
                        if (content.get(parentMediaType) == null) {
                            content.addMediaType(parentMediaType, parentRequestBody.getContent().get(parentMediaType));
                        }
                    }
                }
            }
        }
        
        // handle return type, add as response in case.
        Type returnType = method.getGenericReturnType();
        
        if (annotatedMethod != null && annotatedMethod.getType() != null) {
            returnType = annotatedMethod.getType();
        }
        
        final Class<?> subResource = getSubResourceWithJaxRsSubresourceLocatorSpecs(method);
        if (!shouldIgnoreClass(returnType.getTypeName()) && !method.getGenericReturnType().equals(subResource)) {
            ResolvedSchema resolvedSchema = ModelConverters.getInstance()
                    .resolveAsResolvedSchema(new AnnotatedType(returnType).resolveAsRef(true).jsonViewAnnotation(jsonViewAnnotation));
            if (resolvedSchema.schema != null) {
                Schema returnTypeSchema = resolvedSchema.schema;
                Content content = new Content();
                MediaType mediaType = new MediaType().schema(returnTypeSchema);
                AnnotationsUtils.applyTypes(classProduces == null ? new String[0] : classProduces.value(),
                        methodProduces == null ? new String[0] : methodProduces.value(), content, mediaType);
                if (operation.getResponses() == null) {
                    operation.responses(
                            new ApiResponses()._default(
                                    new ApiResponse().description(DEFAULT_DESCRIPTION)
                                            .content(content)
                            )
                    );
                }
                if (operation.getResponses().getDefault() != null &&
                        StringUtils.isBlank(operation.getResponses().getDefault().get$ref())) {
                    if (operation.getResponses().getDefault().getContent() == null) {
                        operation.getResponses().getDefault().content(content);
                    } else {
                        for (String key : operation.getResponses().getDefault().getContent().keySet()) {
                            if (operation.getResponses().getDefault().getContent().get(key).getSchema() == null) {
                                operation.getResponses().getDefault().getContent().get(key).setSchema(returnTypeSchema);
                            }
                        }
                    }
                }
                Map<String, Schema> schemaMap = resolvedSchema.referencedSchemas;
                if (schemaMap != null) {
                    schemaMap.forEach((key, schema) -> components.addSchemas(key, schema));
                }
                
            }
        }
        if (operation.getResponses() == null || operation.getResponses().isEmpty()) {
            Content content = new Content();
            MediaType mediaType = new MediaType();
            AnnotationsUtils.applyTypes(classProduces == null ? new String[0] : classProduces.value(),
                    methodProduces == null ? new String[0] : methodProduces.value(), content, mediaType);
            
            ApiResponse apiResponseObject = new ApiResponse().description(DEFAULT_DESCRIPTION).content(content);
            operation.setResponses(new ApiResponses()._default(apiResponseObject));
        }
        
        return operation;
    }
    
    
    private static Map<String, Callback> getCallbacks(
            io.swagger.v3.oas.annotations.callbacks.Callback apiCallback,
            Produces methodProduces,
            Produces classProduces,
            Consumes methodConsumes,
            Consumes classConsumes,
            JsonView jsonViewAnnotation) {
        Map<String, Callback> callbackMap = new HashMap<>();
        if (apiCallback == null) {
            return callbackMap;
        }
        
        Callback callbackObject = new Callback();
        if (StringUtils.isNotBlank(apiCallback.ref())) {
            callbackObject.set$ref(apiCallback.ref());
            callbackMap.put(apiCallback.name(), callbackObject);
            return callbackMap;
        }
        PathItem pathItemObject = new PathItem();
        for (io.swagger.v3.oas.annotations.Operation callbackOperation : apiCallback.operation()) {
            Operation callbackNewOperation = new Operation();
            setOperationObjectFromApiOperationAnnotation(
                    callbackNewOperation,
                    callbackOperation,
                    methodProduces,
                    classProduces,
                    methodConsumes,
                    classConsumes,
                    jsonViewAnnotation);
            setPathItemOperation(pathItemObject, callbackOperation.method(), callbackNewOperation);
        }
        
        callbackObject.addPathItem(apiCallback.callbackUrlExpression(), pathItemObject);
        callbackMap.put(apiCallback.name(), callbackObject);
        
        return callbackMap;
    }
    
    
    private static void setPathItemOperation(PathItem pathItemObject, String method, Operation operation) {
        switch (method) {
            case POST_METHOD:
                pathItemObject.post(operation);
                break;
            case GET_METHOD:
                pathItemObject.get(operation);
                break;
            case DELETE_METHOD:
                pathItemObject.delete(operation);
                break;
            case PUT_METHOD:
                pathItemObject.put(operation);
                break;
            case PATCH_METHOD:
                pathItemObject.patch(operation);
                break;
            case TRACE_METHOD:
                pathItemObject.trace(operation);
                break;
            case HEAD_METHOD:
                pathItemObject.head(operation);
                break;
            case OPTIONS_METHOD:
                pathItemObject.options(operation);
                break;
            default:
                // Do nothing here
                break;
        }
    }
    
    private static void setOperationObjectFromApiOperationAnnotation(
            Operation operation,
            io.swagger.v3.oas.annotations.Operation apiOperation,
            Produces methodProduces,
            Produces classProduces,
            Consumes methodConsumes,
            Consumes classConsumes,
            JsonView jsonViewAnnotation) {
        if (StringUtils.isNotBlank(apiOperation.summary())) {
            operation.setSummary(apiOperation.summary());
        }
        if (StringUtils.isNotBlank(apiOperation.description())) {
            operation.setDescription(apiOperation.description());
        }
        if (StringUtils.isNotBlank(apiOperation.operationId())) {
            operation.setOperationId(getOperationId(apiOperation.operationId()));
        }
        if (apiOperation.deprecated()) {
            operation.setDeprecated(apiOperation.deprecated());
        }
        
        ReaderUtils.getStringListFromStringArray(apiOperation.tags()).ifPresent(tags ->
                tags.stream()
                        .filter(t -> operation.getTags() == null || (operation.getTags() != null && !operation.getTags().contains(t)))
                        .forEach(operation::addTagsItem));
        
        if (operation.getExternalDocs() == null) { // if not set in root annotation
            AnnotationsUtils.getExternalDocumentation(apiOperation.externalDocs()).ifPresent(operation::setExternalDocs);
        }
        
        OperationParser
                .getApiResponses(apiOperation.responses(), classProduces, methodProduces, components, jsonViewAnnotation)
                .ifPresent(responses -> {
                    if (operation.getResponses() == null) {
                        operation.setResponses(responses);
                    } else {
                        responses.forEach(operation.getResponses()::addApiResponse);
                    }
                });
        AnnotationsUtils.getServers(apiOperation.servers()).ifPresent(servers -> servers.forEach(operation::addServersItem));
        
        getParametersListFromAnnotation(
                apiOperation.parameters(),
                classConsumes,
                methodConsumes,
                operation,
                jsonViewAnnotation).ifPresent(p -> p.forEach(operation::addParametersItem));
        
        // todo security
        /*Optional<List<SecurityRequirement>> requirementsObject = SecurityParser.getSecurityRequirements(apiOperation.security());
        if (requirementsObject.isPresent()) {
            requirementsObject.get().stream()
                    .filter(r -> operation.getSecurity() == null || !operation.getSecurity().contains(r))
                    .forEach(operation::addSecurityItem);
        }*/
        
        // RequestBody in Operation
        if (apiOperation.requestBody() != null && operation.getRequestBody() == null) {
            OperationParser
                    .getRequestBody(apiOperation.requestBody(), classConsumes, methodConsumes, components, jsonViewAnnotation).ifPresent(
                    operation::setRequestBody);
        }
        
        // Extensions in Operation
        if (apiOperation.extensions().length > 0) {
            Map<String, Object> extensions = AnnotationsUtils.getExtensions(apiOperation.extensions());
            if (extensions != null) {
                extensions.forEach(operation::addExtension);
            }
        }
    }
    
    
    protected static Optional<List<Parameter>> getParametersListFromAnnotation(io.swagger.v3.oas.annotations.Parameter[] parameters,
                                                                               Consumes classConsumes, Consumes methodConsumes,
                                                                               Operation operation, JsonView jsonViewAnnotation) {
        if (parameters == null) {
            return Optional.empty();
        }
        List<Parameter> parametersObject = new ArrayList<>();
        for (io.swagger.v3.oas.annotations.Parameter parameter : parameters) {
            
            ResolvedParameter resolvedParameter = getParameters(ParameterProcessor.getParameterType(parameter),
                    Collections.singletonList(parameter), operation, classConsumes, methodConsumes, jsonViewAnnotation);
            parametersObject.addAll(resolvedParameter.parameters);
        }
        if (parametersObject.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parametersObject);
    }
    
    
    protected static ResolvedParameter getParameters(Type type, List<Annotation> annotations, Operation operation, Consumes classConsumes,
                                                     Consumes methodConsumes, JsonView jsonViewAnnotation) {
        final Iterator<OpenAPIExtension> chain = OpenAPIExtensions.chain();
        if (!chain.hasNext()) {
            return new ResolvedParameter();
        }
        logger.debug("getParameters for {}", type);
        Set<Type> typesToSkip = new HashSet<>();
        final OpenAPIExtension extension = chain.next();
        logger.debug("trying extension {}", extension);
        
        return extension
                .extractParameters(annotations, type, typesToSkip, components, classConsumes, methodConsumes, true, jsonViewAnnotation,
                        chain);
    }
    
    protected static String getOperationId(String operationId) {
        boolean operationIdUsed = existOperationId(operationId);
        String operationIdToFind = null;
        int counter = 0;
        while (operationIdUsed) {
            operationIdToFind = String.format("%s_%d", operationId, ++counter);
            operationIdUsed = existOperationId(operationIdToFind);
        }
        if (operationIdToFind != null) {
            operationId = operationIdToFind;
        }
        return operationId;
    }
    
    
    private static boolean existOperationId(String operationId) {
        if (openAPI == null) {
            return false;
        }
        if (openAPI.getPaths() == null || openAPI.getPaths().isEmpty()) {
            return false;
        }
        for (PathItem path : openAPI.getPaths().values()) {
            Set<String> pathOperationIds = extractOperationIdFromPathItem(path);
            if (pathOperationIds.contains(operationId)) {
                return true;
            }
        }
        return false;
    }
    
    
    private static Set<String> extractOperationIdFromPathItem(PathItem path) {
        Set<String> ids = new HashSet<>();
        if (path.getGet() != null && StringUtils.isNotBlank(path.getGet().getOperationId())) {
            ids.add(path.getGet().getOperationId());
        }
        if (path.getPost() != null && StringUtils.isNotBlank(path.getPost().getOperationId())) {
            ids.add(path.getPost().getOperationId());
        }
        if (path.getPut() != null && StringUtils.isNotBlank(path.getPut().getOperationId())) {
            ids.add(path.getPut().getOperationId());
        }
        if (path.getDelete() != null && StringUtils.isNotBlank(path.getDelete().getOperationId())) {
            ids.add(path.getDelete().getOperationId());
        }
        if (path.getOptions() != null && StringUtils.isNotBlank(path.getOptions().getOperationId())) {
            ids.add(path.getOptions().getOperationId());
        }
        if (path.getHead() != null && StringUtils.isNotBlank(path.getHead().getOperationId())) {
            ids.add(path.getHead().getOperationId());
        }
        if (path.getPatch() != null && StringUtils.isNotBlank(path.getPatch().getOperationId())) {
            ids.add(path.getPatch().getOperationId());
        }
        return ids;
    }
    
    
    protected static void processRequestBody(Parameter requestBodyParameter, Operation operation,
                                             Consumes methodConsumes, Consumes classConsumes,
                                             List<Parameter> operationParameters,
                                             Annotation[] paramAnnotations, Type type,
                                             JsonView jsonViewAnnotation) {
        
        io.swagger.v3.oas.annotations.parameters.RequestBody requestBodyAnnotation = getRequestBody(Arrays.asList(paramAnnotations));
        if (requestBodyAnnotation != null) {
            Optional<RequestBody> optionalRequestBody = OperationParser
                    .getRequestBody(requestBodyAnnotation, classConsumes, methodConsumes, components, jsonViewAnnotation);
            if (optionalRequestBody.isPresent()) {
                RequestBody requestBody = optionalRequestBody.get();
                if (StringUtils.isBlank(requestBody.get$ref()) &&
                        (requestBody.getContent() == null || requestBody.getContent().isEmpty())) {
                    if (requestBodyParameter.getSchema() != null) {
                        Content content = processContent(requestBody.getContent(), requestBodyParameter.getSchema(), methodConsumes,
                                classConsumes);
                        requestBody.setContent(content);
                    }
                } else if (StringUtils.isBlank(requestBody.get$ref()) &&
                        requestBody.getContent() != null &&
                        !requestBody.getContent().isEmpty()) {
                    if (requestBodyParameter.getSchema() != null) {
                        for (MediaType mediaType : requestBody.getContent().values()) {
                            if (mediaType.getSchema() == null) {
                                if (requestBodyParameter.getSchema() == null) {
                                    mediaType.setSchema(new Schema());
                                } else {
                                    mediaType.setSchema(requestBodyParameter.getSchema());
                                }
                            }
                            if (StringUtils.isBlank(mediaType.getSchema().getType())) {
                                mediaType.getSchema().setType(requestBodyParameter.getSchema().getType());
                            }
                        }
                    }
                }
                operation.setRequestBody(requestBody);
            }
        } else {
            if (operation.getRequestBody() == null) {
                boolean isRequestBodyEmpty = true;
                RequestBody requestBody = new RequestBody();
                if (StringUtils.isNotBlank(requestBodyParameter.get$ref())) {
                    requestBody.set$ref(requestBodyParameter.get$ref());
                    isRequestBodyEmpty = false;
                }
                if (StringUtils.isNotBlank(requestBodyParameter.getDescription())) {
                    requestBody.setDescription(requestBodyParameter.getDescription());
                    isRequestBodyEmpty = false;
                }
                if (Boolean.TRUE.equals(requestBodyParameter.getRequired())) {
                    requestBody.setRequired(requestBodyParameter.getRequired());
                    isRequestBodyEmpty = false;
                }
                
                if (requestBodyParameter.getSchema() != null) {
                    Content content = processContent(null, requestBodyParameter.getSchema(), methodConsumes, classConsumes);
                    requestBody.setContent(content);
                    isRequestBodyEmpty = false;
                }
                if (!isRequestBodyEmpty) {
                    //requestBody.setExtensions(extensions);
                    operation.setRequestBody(requestBody);
                }
            }
        }
    }
    
    
    protected static Content processContent(Content content, Schema schema, Consumes methodConsumes, Consumes classConsumes) {
        if (content == null) {
            content = new Content();
        }
        if (methodConsumes != null) {
            for (String value : methodConsumes.value()) {
                setMediaTypeToContent(schema, content, value);
            }
        } else if (classConsumes != null) {
            for (String value : classConsumes.value()) {
                setMediaTypeToContent(schema, content, value);
            }
        } else {
            setMediaTypeToContent(schema, content, DEFAULT_MEDIA_TYPE_VALUE);
        }
        return content;
    }
    
    
    private static void setMediaTypeToContent(Schema schema, Content content, String value) {
        MediaType mediaTypeObject = new MediaType();
        mediaTypeObject.setSchema(schema);
        content.addMediaType(value, mediaTypeObject);
    }
    
    private static io.swagger.v3.oas.annotations.parameters.RequestBody getRequestBody(List<Annotation> annotations) {
        if (annotations == null) {
            return null;
        }
        for (Annotation a : annotations) {
            if (a instanceof io.swagger.v3.oas.annotations.parameters.RequestBody) {
                return (io.swagger.v3.oas.annotations.parameters.RequestBody) a;
            }
        }
        return null;
    }
    
    
}
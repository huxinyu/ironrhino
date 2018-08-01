package org.ironrhino.core.remoting.server;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolationException;
import javax.validation.ValidationException;

import org.ironrhino.core.remoting.RemotingContext;
import org.ironrhino.core.remoting.ServiceRegistry;
import org.ironrhino.core.remoting.serializer.HttpInvokerSerializer;
import org.ironrhino.core.remoting.serializer.HttpInvokerSerializers;
import org.ironrhino.core.remoting.serializer.JavaHttpInvokerSerializer;
import org.ironrhino.core.remoting.stats.ServiceStats;
import org.ironrhino.core.servlet.AccessFilter;
import org.ironrhino.core.servlet.ProxySupportHttpServletRequest;
import org.ironrhino.core.util.ExceptionUtils;
import org.ironrhino.core.util.JsonDesensitizer;
import org.ironrhino.core.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.http.HttpHeaders;
import org.springframework.remoting.httpinvoker.HttpInvokerServiceExporter;
import org.springframework.remoting.support.RemoteInvocation;
import org.springframework.remoting.support.RemoteInvocationResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpInvokerServer extends HttpInvokerServiceExporter {

	private Logger remotingLogger = LoggerFactory.getLogger("remoting");

	private static ThreadLocal<Class<?>> serviceInterface = new ThreadLocal<>();

	private static ThreadLocal<Object> service = new ThreadLocal<>();

	private static ThreadLocal<HttpInvokerSerializer> serializer = ThreadLocal
			.withInitial(() -> JavaHttpInvokerSerializer.INSTANCE);

	private Map<String, Object> exportedServices = Collections.emptyMap();

	private Map<String, Class<?>> interfaces = Collections.emptyMap();

	private Map<Class<?>, Object> proxies = Collections.emptyMap();

	@Value("${httpInvoker.loggingPayload:true}")
	private boolean loggingPayload;

	@Autowired
	private ServiceRegistry serviceRegistry;

	@Autowired(required = false)
	private ServiceStats serviceStats;

	@Override
	public void handleRequest(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		if (request instanceof ProxySupportHttpServletRequest) {
			log.error("Forbidden for Proxy");
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		RemotingContext.setRequestFrom(request.getHeader(AccessFilter.HTTP_HEADER_REQUEST_FROM));
		Enumeration<String> en = request.getHeaderNames();
		while (en.hasMoreElements()) {
			String name = en.nextElement();
			if (name.startsWith(RemotingContext.HTTP_HEADER_PREFIX)) {
				String key = URLDecoder.decode(name.substring(RemotingContext.HTTP_HEADER_PREFIX.length()), "UTF-8");
				String value = URLDecoder.decode(request.getHeader(name), "UTF-8");
				RemotingContext.put(key, value);
			}
		}
		String uri = request.getRequestURI();
		String interfaceName = uri.substring(uri.lastIndexOf('/') + 1);
		Class<?> clazz = interfaces.get(interfaceName);
		if (clazz == null) {
			log.error("Service Not Found: " + interfaceName);
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		serviceInterface.set(clazz);
		service.set(exportedServices.get(interfaceName));
		Object proxy = getProxyForService();
		try {
			serializer.set(HttpInvokerSerializers.ofContentType(request.getHeader(HttpHeaders.CONTENT_TYPE)));
			RemoteInvocation invocation = readRemoteInvocation(request, request.getInputStream());
			RemoteInvocationResult result = call(() -> invokeAndCreateResult(request, invocation, proxy));
			if (result == null) {
				// async
				return;
			}
			writeRemoteInvocationResult(request, response, invocation, result);
		} catch (SerializationFailedException sfe) {
			log.error(sfe.getMessage(), sfe);
			response.setHeader(RemotingContext.HTTP_HEADER_EXCEPTION_MESSAGE, sfe.getMessage());
			response.setStatus(RemotingContext.SC_SERIALIZATION_FAILED);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		} finally {
			serviceInterface.remove();
			service.remove();
			serializer.remove();
			RemotingContext.clear();
			MDC.remove("role");
			MDC.remove("service");
		}
	}

	@Override
	public void prepare() {
		if (serviceRegistry != null) {
			exportedServices = serviceRegistry.getExportedServices();
			proxies = new HashMap<>(exportedServices.size() * 3 / 2 + 1, 1f);
			interfaces = new HashMap<>(exportedServices.size() * 3 / 2 + 1, 1f);
			for (Map.Entry<String, Object> entry : exportedServices.entrySet()) {
				try {
					Class<?> intf = Class.forName(entry.getKey());
					serviceInterface.set(intf);
					service.set(entry.getValue());
					proxies.put(intf, super.getProxyForService());
					interfaces.put(entry.getKey(), intf);
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				}
			}
			serviceInterface.remove();
			service.remove();
		}
	}

	@Override
	public Object getService() {
		return service.get();
	}

	@Override
	public Class<?> getServiceInterface() {
		return serviceInterface.get();
	}

	@Override
	protected Object getProxyForService() {
		return proxies.get(getServiceInterface());
	}

	@Override
	public String getContentType() {
		return serializer.get().getContentType();
	}

	@Override
	protected RemoteInvocation readRemoteInvocation(HttpServletRequest request, InputStream is)
			throws IOException, ClassNotFoundException {
		RemoteInvocation invocation = serializer.get().readRemoteInvocation(decorateInputStream(request, is));
		log(invocation);
		return invocation;
	}

	protected RemoteInvocationResult invokeAndCreateResult(HttpServletRequest request, RemoteInvocation invocation,
			Object targetObject) {
		RemoteInvocationResult result;
		try {
			Object value = invoke(invocation, targetObject);
			result = new RemoteInvocationResult(value);
		} catch (Throwable ex) {
			result = new RemoteInvocationResult(ex);
		}
		if (!result.hasException())
			result = transformResult(request, invocation, targetObject, result);
		return result;
	}

	protected RemoteInvocationResult transformResult(HttpServletRequest request, RemoteInvocation invocation,
			Object targetObject, RemoteInvocationResult result) {
		Object value = result.getValue();
		if (value instanceof Optional) {
			Optional<?> optional = ((Optional<?>) value);
			result.setValue(optional.isPresent() ? optional.get() : null);
		} else if (value instanceof Callable || value instanceof Future) {
			Map<String, String> contextMap = MDC.getCopyOfContextMap();
			AsyncContext context = request.startAsync();
			context.start(() -> {
				MDC.setContextMap(contextMap);
				HttpServletResponse response = (HttpServletResponse) context.getResponse();
				try {
					RemoteInvocationResult remoteInvocationResult = call(() -> {
						try {
							result.setValue(value instanceof Callable ? (((Callable<?>) value)).call()
									: (((Future<?>) value)).get());
						} catch (Throwable e) {
							result.setValue(null);
							result.setException(e);
						}
						return result;
					});
					writeRemoteInvocationResult(request, response, remoteInvocationResult);
				} catch (SerializationFailedException sfe) {
					log.error(sfe.getMessage(), sfe);
					response.setHeader(RemotingContext.HTTP_HEADER_EXCEPTION_MESSAGE, sfe.getMessage());
					response.setStatus(RemotingContext.SC_SERIALIZATION_FAILED);
				} catch (Exception ex) {
					log.error(ex.getMessage(), ex);
					response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				} finally {
					context.complete();
					MDC.clear();
				}
			});
			return null;
		}
		return result;
	}

	protected void writeRemoteInvocationResult(HttpServletRequest request, HttpServletResponse response,
			RemoteInvocation invocation, RemoteInvocationResult result) throws IOException {
		response.setHeader("Keep-Alive", "timeout=30, max=600");
		if (result.hasInvocationTargetException()) {
			try {
				InvocationTargetException ite = (InvocationTargetException) result.getException();
				if (ite != null)
					ReflectionUtils.setFieldValue(ite, "target", translateAndTrim(ite.getTargetException(), 10));
			} catch (Exception ex) {
			}
		}
		response.setContentType(serializer.get().getContentType());
		serializer.get().writeRemoteInvocationResult(invocation, result,
				decorateOutputStream(request, response, response.getOutputStream()));
	}

	protected Throwable translateAndTrim(Throwable throwable, int maxStackTraceElements) {
		if (throwable instanceof ConstraintViolationException) {
			ValidationException ve = new ValidationException(throwable.getMessage());
			ve.setStackTrace(throwable.getStackTrace());
			throwable = ve;
		}
		ExceptionUtils.trimStackTrace(throwable, maxStackTraceElements);
		return throwable;
	}

	private void log(RemoteInvocation invocation) {
		Class<?> clazz = getServiceInterface();
		List<String> parameterTypeList = new ArrayList<>(invocation.getParameterTypes().length);
		for (Class<?> cl : invocation.getParameterTypes())
			parameterTypeList.add(cl.getSimpleName());
		String method = new StringBuilder(invocation.getMethodName()).append("(")
				.append(String.join(",", parameterTypeList)).append(")").toString();
		MDC.put("role", "SERVER");
		MDC.put("service", clazz.getName() + '.' + method);
		if (loggingPayload) {
			Object[] args = invocation.getArguments();
			remotingLogger.info("Request: {}",
					JsonDesensitizer.DEFAULT_INSTANCE.toJson(args.length == 1 ? args[0] : invocation.getArguments()));
		}
	}

	private RemoteInvocationResult call(Callable<RemoteInvocationResult> callable) throws Exception {
		long time = System.currentTimeMillis();
		RemoteInvocationResult result = callable.call();
		if (result == null) {
			// async
			return null;
		}
		time = System.currentTimeMillis() - time;
		if (serviceStats != null) {
			String service = MDC.get("service");
			int index = service.substring(0, service.indexOf('(')).lastIndexOf('.');
			serviceStats.serverSideEmit(service.substring(0, index), service.substring(index + 1), time);
		}
		if (loggingPayload) {
			if (!result.hasException()) {
				Object value = result.getValue();
				remotingLogger.info("Response: {}", JsonDesensitizer.DEFAULT_INSTANCE.toJson(value));
			} else {
				Throwable throwable = result.getException();
				if (throwable != null && throwable.getCause() != null)
					throwable = throwable.getCause();
				remotingLogger.error("Error:", throwable);
			}
		}
		remotingLogger.info("Invoked from {} in {}ms", RemotingContext.getRequestFrom(), time);
		return result;
	}

}

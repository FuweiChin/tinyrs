package net.bldgos.tinyrs;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

public abstract class TinyrsDispatcherServlet extends GenericServlet {
	private static final long serialVersionUID = 3072968609000472207L;
	private static Logger LOGGER=Logger.getLogger(TinyrsDispatcherServlet.class.getName());
	private Map<String,List<ResourceMethod>> resourceMapping=new LinkedHashMap<>();
	@Override
	public void init() throws ServletException {
		WebServlet webServletAnno=this.getClass().getAnnotation(WebServlet.class);
		if(webServletAnno!=null) {
			for(String urlPattern:webServletAnno.urlPatterns()) {
				if(!urlPattern.endsWith("/*")) {
					throw new ServletException("tinyrs servlet registeration error: url pattern must ends with /*");
				}
			}
		}
		for(Method method:this.getClass().getDeclaredMethods()) {
			//detect resource method
			List<Annotation> httpMethodAnnotations=new ArrayList<>();
			if(method.isAnnotationPresent(GET.class)) {
				httpMethodAnnotations.add(method.getAnnotation(GET.class));
			}
			if(method.isAnnotationPresent(POST.class)) {
				httpMethodAnnotations.add(method.getAnnotation(POST.class));
			}
			if(method.isAnnotationPresent(PUT.class)) {
				httpMethodAnnotations.add(method.getAnnotation(PUT.class));
			}
			if(method.isAnnotationPresent(DELETE.class)) {
				httpMethodAnnotations.add(method.getAnnotation(DELETE.class));
			}
			if(method.isAnnotationPresent(PATCH.class)) {
				httpMethodAnnotations.add(method.getAnnotation(PATCH.class));
			}
			if(method.isAnnotationPresent(OPTIONS.class)) {
				httpMethodAnnotations.add(method.getAnnotation(OPTIONS.class));
			}
			if(method.isAnnotationPresent(HEAD.class)) {
				httpMethodAnnotations.add(method.getAnnotation(HEAD.class));
			}
			//should be annotated with at least one of @GET @POST @PUT @DELETE @PATCH @OPTIONS @HEAD
			if(httpMethodAnnotations.size()==0) {
				continue;
			}
			ResourceMethod resourceMethod=new ResourceMethod();
			//resource method should be public
			if(!Modifier.isPublic(method.getModifiers())) {
				throw new ServletException("resource method should be have modifier public");
			}
			//should return type void
			if(!method.getReturnType().equals(Void.TYPE)) {
				throw new ServletException("resource method should have return type void");
			}
			//should have 2 parameters (HttpServletRequest,HttpServletResponse)
			Class<?>[] parameterTypes=method.getParameterTypes();
			if(parameterTypes.length!=2||parameterTypes[0]!=HttpServletRequest.class||parameterTypes[1]!=HttpServletResponse.class) {
				throw new ServletException("resource method should have 2 parameters (HttpServletRequest,HttpServletResponse)");
			}
			resourceMethod.setReflectedMethod(method);
			String httpMethod=httpMethodAnnotations.get(0).annotationType().getAnnotation(HttpMethod.class).value();
			resourceMethod.setHttpMethod(httpMethod);
			//if annotated @Path present, its @Path value should start with /
			Path pathAnno=method.getAnnotation(Path.class);
			String path;
			if(pathAnno==null) {
				path="/";
			}else {
				path=pathAnno.value();
				if(!path.startsWith("/")) {
					throw new ServletException("value of @Path should start with /");
				}
			}
			resourceMethod.setPath(path);
			Consumes consumesAnno=method.getAnnotation(Consumes.class);
			resourceMethod.setConsumes(consumesAnno==null?null:consumesAnno.value());
			Produces producesAnno=method.getAnnotation(Produces.class);
			resourceMethod.setProduces(producesAnno==null?null:producesAnno.value());
			List<ResourceMethod> group=resourceMapping.get(path);
			if(group==null) {
				group=new ArrayList<>();
				resourceMapping.put(path, group);
			}
			//path+httpMethod should be unique in a Servlet
			if(group.indexOf(resourceMethod)!=-1) {
				throw new ServletException("A method with same HTTP method and path already exists");
			}
			group.add(resourceMethod);
		}
		LOGGER.info("tinyrs resouce servlet "+this.getClass().getName()+" initialized");
	}

	@Override
	public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
		HttpServletRequest request=(HttpServletRequest)req;
		HttpServletResponse response=(HttpServletResponse)res;
		// #1 matching phase
		String pathInfo=request.getPathInfo();
		if(pathInfo==null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		ResourceMethod resourceMethod=null;
		List<ResourceMethod> group=resourceMapping.get(pathInfo);
		if(group==null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		resourceMethod=new ResourceMethod();
		resourceMethod.setHttpMethod(request.getMethod());
		resourceMethod.setPath(pathInfo);
		int index=group.indexOf(resourceMethod);
		if(index==-1) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		resourceMethod=group.get(index);
		String[] produces=resourceMethod.getProduces();
		if(produces!=null&&produces.length==1) {//send response header Content-Type only when producing one MediaType
			response.setHeader("Content-Type", produces[0]);
		}
		Method method=resourceMethod.getReflectedMethod();
		try {
			method.invoke(this, request, response);
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}
	}
}

class ResourceMethod {
	private Method reflectedMethod;
	private String httpMethod;
	private String path;
	private String[] consumes;
	private String[] produces;
	public Method getReflectedMethod() {
		return reflectedMethod;
	}
	public void setReflectedMethod(Method reflectedMethod) {
		this.reflectedMethod = reflectedMethod;
	}
	public String getHttpMethod() {
		return httpMethod;
	}
	public void setHttpMethod(String httpMethod) {
		this.httpMethod = httpMethod;
	}
	public String getPath() {
		return path;
	}
	public void setPath(String path) {
		this.path = path;
	}
	public String[] getConsumes() {
		return consumes;
	}
	public void setConsumes(String[] consumes) {
		this.consumes = consumes;
	}
	public String[] getProduces() {
		return produces;
	}
	public void setProduces(String[] produces) {
		this.produces = produces;
	}
	@Override
	public boolean equals(Object obj) {
		if(obj instanceof ResourceMethod) {
			ResourceMethod that=(ResourceMethod)obj;
			return this.path.equals(that.path)&&
					this.httpMethod.equals(that.httpMethod);
		}
		return false;
	}
	@Override
	public int hashCode() {
		return this.path.hashCode()+this.httpMethod.hashCode();
	}
}

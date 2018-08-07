package net.bldgos.tinyrs;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
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
import javax.ws.rs.core.MediaType;

public abstract class TinyrsDispatcherServlet extends GenericServlet {
	private static final long serialVersionUID = 3072968609000472207L;
	private static Logger LOGGER=Logger.getLogger(TinyrsDispatcherServlet.class.getName());
	private Map<String,List<ResourceMethod>> resourceMapping=new LinkedHashMap<>();
	private List<String> UNSAFE_METHODS=Arrays.asList("POST","PUT","PATCH","DELETE");
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
			for(int i=0; i<httpMethodAnnotations.size(); i++) {
				resourceMethod.setHttpMethod(httpMethodAnnotations.get(i).annotationType().getAnnotation(HttpMethod.class).value());
				//path+httpMethod should be unique in a Servlet
				if(group.indexOf(resourceMethod)!=-1) {
					throw new ServletException("A method with same HTTP method and path already exists");
				}
				ResourceMethod rm=null;
				try {
					rm = (ResourceMethod) resourceMethod.clone();
				} catch (CloneNotSupportedException e) {
				}
				group.add(rm);
			}
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
		List<ResourceMethod> group=resourceMapping.get(pathInfo);
		if(group==null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		ResourceMethod rm=new ResourceMethod();
		rm.setHttpMethod(request.getMethod());
		rm.setPath(pathInfo);
		int index=group.indexOf(rm);
		if(index==-1) {
			response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}
		ResourceMethod resourceMethod=group.get(index);
		// #2 validating phase
		List<MediaType> produceTypes=resourceMethod.getProduceTypes();
		if(produceTypes!=null) {
			List<MediaType> acceptTypes=parseAccept(request.getHeader("Accept"));
			boolean accepted=produceTypes.stream().anyMatch((produceType)->{
				return acceptTypes.stream().anyMatch((acceptType)->acceptType.isCompatible(produceType));
			});
			if(!accepted) {
				response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE);
				return;
			}
		}
		if(UNSAFE_METHODS.contains(request.getMethod())) {
			List<MediaType> consumeTypes=resourceMethod.getConsumeTypes();
			String contentType=request.getHeader("Content-Type");
			if(consumeTypes!=null&&contentType!=null) {
				boolean accepted=consumeTypes.stream().anyMatch((consumeType)->{
					return consumeType.isCompatible(MediaType.valueOf(contentType));
				});
				if(!accepted) {
					response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE);
					return;
				}
			}
		}
		// #3 dispatching phase
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
	private static List<MediaType> parseAccept(String accept){
		List<MediaType> types=new ArrayList<>();
		for(String type:accept.split(", ?")) {
			types.add(MediaType.valueOf(type));
		}
		return types;
	}
}

class ResourceMethod {
	private Method reflectedMethod;
	private String httpMethod;
	private String path;
	private String[] consumes;
	private String[] produces;
	private transient List<MediaType> consumeTypes;
	private transient List<MediaType> produceTypes;
	
	public ResourceMethod() {
		
	}
	public ResourceMethod(Method reflectedMethod, String httpMethod, String path, String[] consumes, String[] produces) {
		this.reflectedMethod = reflectedMethod;
		this.httpMethod = httpMethod;
		this.path = path;
		this.consumes = consumes;
		this.produces = produces;
	}
	
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
	public List<MediaType> getConsumeTypes() {
		if(consumes==null)
			return null;
		List<MediaType> mediaTypes=consumeTypes;
		if(mediaTypes!=null)
			return mediaTypes;
		synchronized (consumes) {
			mediaTypes=consumeTypes;
			if(mediaTypes!=null)
				return mediaTypes;
			mediaTypes=new ArrayList<>();
			for(String consume:consumes) {
				mediaTypes.add(MediaType.valueOf(consume));
			}
			this.consumeTypes=mediaTypes;
			return mediaTypes;
		}
	}
	public List<MediaType> getProduceTypes() {
		if(produces==null)
			return null;
		List<MediaType> mediaTypes=produceTypes;
		if(mediaTypes!=null)
			return mediaTypes;
		synchronized (produces) {
			mediaTypes=produceTypes;
			if(mediaTypes!=null)
				return mediaTypes;
			mediaTypes=new ArrayList<>();
			for(String consume:produces) {
				mediaTypes.add(MediaType.valueOf(consume));
			}
			this.produceTypes=mediaTypes;
			return mediaTypes;
		}
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
	@Override
	protected Object clone() throws CloneNotSupportedException {
		return new ResourceMethod(reflectedMethod, httpMethod, path, consumes, produces);
	}
}

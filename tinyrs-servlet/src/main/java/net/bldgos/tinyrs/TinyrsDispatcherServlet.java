package net.bldgos.tinyrs;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
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
	private static List<Class<? extends Annotation>> HTTP_METHOD_ANNONTATIONS=Arrays.asList(GET.class,POST.class,PUT.class,DELETE.class,PATCH.class,HEAD.class,OPTIONS.class);

	private Logger LOGGER=Logger.getLogger(this.getClass().getName());
	protected Map<String,List<ResourceMethod>> resourceMapping=new LinkedHashMap<>();

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
			List<String> httpMethods=new ArrayList<>();
			for(Class<? extends Annotation> c:HTTP_METHOD_ANNONTATIONS) {
				Annotation a=method.getAnnotation(c);
				if(a!=null) {
					httpMethods.add(a.annotationType().getAnnotation(HttpMethod.class).value());
				}
			}
			//should be annotated with at least one of @GET @POST @PUT @DELETE @PATCH @OPTIONS @HEAD
			if(httpMethods.size()==0) {
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
			for(int i=0; i<httpMethods.size(); i++) {
				resourceMethod.setHttpMethod(httpMethods.get(i));
				//path+httpMethod should be unique in a Servlet
				int index=group.indexOf(resourceMethod);
				if(index!=-1) {
					throw new ServletException("There exists another java method with the same http method and path: "+group.get(index).getReflectedMethod());
				}
				group.add(new ResourceMethod(resourceMethod.getReflectedMethod(), resourceMethod.getHttpMethod(), resourceMethod.getPath(), resourceMethod.getConsumes(), resourceMethod.getProduces()));
			}
		}
		LOGGER.info("tinyrs resouce servlet "+this.getClass().getName()+" initialized");
	}

	@Override
	public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
		if (!(req instanceof HttpServletRequest && res instanceof HttpServletResponse)) {
			throw new ServletException("non-HTTP request or response");
		}
		HttpServletRequest request=(HttpServletRequest)req;
		HttpServletResponse response=(HttpServletResponse)res;
		String m=request.getMethod();
		switch(m) {
		case "GET":
		case "POST":
		case "PUT":
		case "PATCH":
		case "DELETE":
		case "TRACE":
		case "OPTIONS":
			break;
		case "HEAD":
			response=new NoBodyResponse(response);
			break;
		default:
			response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
			break;
		}
		// #1 matching phase
		String pathInfo=request.getPathInfo();
		List<ResourceMethod> group=null;
		if(pathInfo==null||
				(group=resourceMapping.get(pathInfo))==null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		ResourceMethod rm=new ResourceMethod();
		rm.setHttpMethod(request.getMethod());
		rm.setPath(pathInfo);
		int index=group.indexOf(rm);
		checkAgent:if(index==-1) {
			switch(request.getMethod()) {
			case "TRACE":
				this.doTrace(request, response);
				return;
			case "OPTIONS":
				this.doOptions(request, response);
				return;
			case "HEAD":
				rm.setHttpMethod("GET");
				index=group.indexOf(rm);
				if(index==-1) {
					response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
				}
				break checkAgent;
			default:
				response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
				return;
			}
		}
		ResourceMethod resourceMethod=group.get(index);
		// #2 validating phase
		List<MediaType> produceTypes=resourceMethod.getProduceTypes();
		if(produceTypes!=null) {//check request header Accept
			List<MediaType> acceptTypes=parseAccept(request.getHeader("Accept"));
			if(!produceTypes.stream().anyMatch((produceType)->acceptTypes.stream().anyMatch((acceptType)->acceptType.isCompatible(produceType)))) {
				response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE);
				return;
			}
		}
		switch(request.getMethod()) {//check request header Content-Type
		case "POST":
		case "PUT":
		case "PATCH":
		case "DELETE":
			List<MediaType> consumeTypes=resourceMethod.getConsumeTypes();
			String contentType=request.getContentType();
			if(consumeTypes!=null&&contentType!=null) {
				if(!consumeTypes.stream().anyMatch((consumeType)->consumeType.isCompatible(MediaType.valueOf(contentType)))) {
					response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE);
					return;
				}
			}
			break;
		}
		// #3 dispatching phase
		String[] produces=resourceMethod.getProduces();
		if(produces!=null&&produces.length==1) {//send response header Content-Type only when producing one MediaType
			response.setContentType(produces[0]);
		}
		try {
			switch(request.getMethod()) {
			case "HEAD":
				NoBodyResponse noBodyResponse=new NoBodyResponse(response);
				resourceMethod.getReflectedMethod().invoke(this, request, response);
				noBodyResponse.setContentLength();
				break;
			default:
				resourceMethod.getReflectedMethod().invoke(this, request, response);
				break;
			}
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}
	}
	private void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		StringBuilder allow = new StringBuilder();
		allow.append("OPTIONS");
		for (ResourceMethod resourceMethod:resourceMapping.get(request.getPathInfo())) {
			String httpMethod=resourceMethod.getHttpMethod();
			allow.append(", ").append(httpMethod);
			if(httpMethod.equals("PATCH")) {
				String[] consumes=resourceMethod.getConsumes();
				if(consumes!=null&&consumes.length>0) {
					StringBuilder acceptPatch=new StringBuilder();
					acceptPatch.append(consumes[0]);
					for(int i=1; i<consumes.length; i++) {
						acceptPatch.append(", ").append(consumes[i]);
					}
					response.setHeader("Accept-Patch", acceptPatch.toString());
				}
			}
		}
		response.setHeader("Allow", allow.toString());
	}
	private void doTrace(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		final String CRLF = "\r\n";
		StringBuilder buffer = new StringBuilder();
		buffer.append("TRACE ").append(request.getRequestURI()).append(request.getQueryString()==null?"":"?"+request.getQueryString()).append(" ").append(request.getProtocol()).append(CRLF);
		for(Enumeration<String> e = request.getHeaderNames(); e.hasMoreElements(); ) {
			String headerName = e.nextElement();
			buffer.append(headerName).append(": ").append(request.getHeader(headerName)).append(CRLF);
		}
		response.setContentType("message/http");
		response.setContentLength(buffer.length());
		try(ServletOutputStream output = response.getOutputStream();){
			output.print(buffer.toString());
		}
	}
	private static List<MediaType> parseAccept(String accept){
		List<MediaType> types=new ArrayList<>();
		if(accept==null)
			return types;
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

class NoBodyResponse extends HttpServletResponseWrapper {

	private static final ResourceBundle lStrings = ResourceBundle.getBundle("javax.servlet.http.LocalStrings");

	private NoBodyOutputStream noBody;
	private PrintWriter writer;
	private boolean didSetContentLength;
	private boolean usingOutputStream;

	// file private
	NoBodyResponse(HttpServletResponse r) {
		super(r);
		noBody = new NoBodyOutputStream();
	}

	// file private
	void setContentLength() {
		if (!didSetContentLength) {
			if (writer != null) {
				writer.flush();
			}
			setContentLength(noBody.getContentLength());
		}
	}

	@Override
	public void setContentLength(int len) {
		super.setContentLength(len);
		didSetContentLength = true;
	}

	@Override
	public void setContentLengthLong(long len) {
		super.setContentLengthLong(len);
		didSetContentLength = true;
	}

	@Override
	public void setHeader(String name, String value) {
		super.setHeader(name, value);
		checkHeader(name);
	}

	@Override
	public void addHeader(String name, String value) {
		super.addHeader(name, value);
		checkHeader(name);
	}

	@Override
	public void setIntHeader(String name, int value) {
		super.setIntHeader(name, value);
		checkHeader(name);
	}

	@Override
	public void addIntHeader(String name, int value) {
		super.addIntHeader(name, value);
		checkHeader(name);
	}

	private void checkHeader(String name) {
		if ("content-length".equalsIgnoreCase(name)) {
			didSetContentLength = true;
		}
	}

	@Override
	public ServletOutputStream getOutputStream() throws IOException {

		if (writer != null) {
			throw new IllegalStateException(lStrings.getString("err.ise.getOutputStream"));
		}
		usingOutputStream = true;

		return noBody;
	}

	@Override
	public PrintWriter getWriter() throws UnsupportedEncodingException {

		if (usingOutputStream) {
			throw new IllegalStateException(lStrings.getString("err.ise.getWriter"));
		}

		if (writer == null) {
			OutputStreamWriter w = new OutputStreamWriter(noBody, getCharacterEncoding());
			writer = new PrintWriter(w);
		}

		return writer;
	}
}

/*
 * Servlet output stream that gobbles up all its data.
 */
// file private
class NoBodyOutputStream extends ServletOutputStream {

	private static ResourceBundle lStrings = ResourceBundle.getBundle("javax.servlet.http.LocalStrings");

	private int contentLength = 0;

	// file private
	NoBodyOutputStream() {
	}

	// file private
	int getContentLength() {
		return contentLength;
	}

	@Override
	public void write(int b) {
		contentLength++;
	}

	@Override
	public void write(byte buf[], int offset, int len) throws IOException {
		if (buf == null) {
			throw new NullPointerException(lStrings.getString("err.io.nullArray"));
		}
		if (offset < 0 || len < 0 || offset + len > buf.length) {
			String msg = lStrings.getString("err.io.indexOutOfBounds");
			Object[] msgArgs = new Object[3];
			msgArgs[0] = Integer.valueOf(offset);
			msgArgs[1] = Integer.valueOf(len);
			msgArgs[2] = Integer.valueOf(buf.length);
			msg = MessageFormat.format(msg, msgArgs);
			throw new IndexOutOfBoundsException(msg);
		}

		contentLength += len;
	}

	public boolean isReady() {
		return false;
	}

	public void setWriteListener(WriteListener writeListener) {

	}
}
# tinyrs
A Servlet dispatcher just like HttpServlet but supports JAX-RS style path routing.

## Why tinyrs?
+ tinyrs uses  HttpServlet-like request dispatching and JAX-RS-like path routing
+ tinyrs is tandard-ready, easy to learn and small in size
+ Similar library like [HTTP-RPC](https://github.com/gk-brown/HTTP-RPC) is just not light-weight enough, so tinyrs may be more.

If you need a balanced solution between feature-rich web framework and small-beautiful Servlet, tinyrs is the right choice.

## Install
1. Checkout the tinyrs project
2. Include the following dependency to your maven pom.xml

```
<dependency>
	<groupId>net.bldgos</groupId>
	<artifactId>tinyrs-servlet</artifactId>
	<version>0.1.0</version>
</dependency>
```

## Usage
1. Create a servlet class which will extend TinyrsDispatcherServlet
2. Register the servlet through deployment descriptor(web.xml) or annotation(@WebServlet) like /appPath/*
3. Add a `public void` method with parameters `(HttpServletRequest request, HttpServletResponse response)`
4. Add one or more http method annotations(@GET,@POST,@PUT,@PATCH,@DELETE,@OPTIONS,@HEAD), and rest optional @Path, @Produces, @Consumes
5. Fill logic code in the method, just like doGet(), doPost(), ..., the HttpServlet way

Here is a example:

```
@WebServlet(urlPatterns="/v1/*")
public class GreetingServlet extends TinyrsDispatcherServlet {
	private static final long serialVersionUID = -1346032693813268430L;

	@GET
	@Path("/hello")
	@Produces("text/plain")
	public void sayHello(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try( PrintWriter out=response.getWriter() ){
			out.write("Hello");
		}
	}

}
```

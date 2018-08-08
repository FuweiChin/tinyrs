package net.bldgos.tinyrs.web.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import net.bldgos.tinyrs.TinyrsDispatcherServlet;

@WebServlet(urlPatterns="/v1/*",asyncSupported=true)
@MultipartConfig(maxRequestSize=1*1024*1024*1024+1024,maxFileSize=1*1024*1024*1024,fileSizeThreshold=8*1024*1024)
public class V1Servlet extends TinyrsDispatcherServlet {
	private static final long serialVersionUID = -1346032693813268430L;

	public static Map<String,Object> tokenRegistry=new ConcurrentHashMap<>();
	public static File repoDir=new File(System.getProperty("user.home"),".tinyrs/repository");

	public V1Servlet() {
		repoDir.mkdirs();
	}

	@GET
	@Path("/")
	@Produces(MediaType.TEXT_HTML)
	public void getWelcomePage(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		request.getRequestDispatcher("/WEB-INF/v1/index.jsp").forward(request, response);
	}

	@POST
	@Path("/")
	@Produces(MediaType.TEXT_HTML)
	public void postWelcomeINfo(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String message=request.getParameter("message");
		try(PrintWriter out=response.getWriter();){
			out.println(message);
		}
	}

	@POST
	@Path("/login")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.TEXT_PLAIN)
	public void login(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String username=request.getParameter("username");
		String password=request.getParameter("password");
		if(username==null||password==null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
		}
		PrintWriter out=response.getWriter();
		try{
			authenticate(username,password);
			String token=issueToken(username);
			out.write("0:OK:"+token);
		}catch (LoginException e) {
			out.write("0:ERROR:"+e);
		}finally {
			out.close();
		}
	}

	private void authenticate(String username,String password) throws LoginException {
		if(username.equals("admin")&&password.equals("istrator")) {
		}else {
			throw new FailedLoginException("Incorrect username or password");
		}
	}

	private String issueToken(String username) {
		String token=UUID.randomUUID().toString().replaceAll("-", "");
		Date issuedAt=new Date();
		tokenRegistry.put(token, issuedAt);
		return token;
	}
	
	@POST
	@Path("/upload")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces(MediaType.TEXT_PLAIN)
	public void upload(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		int count=0;
		for(Part part:request.getParts()) {
			String fileName=part.getSubmittedFileName();
			String name=part.getName();
			if(fileName==null||!name.equals("file"))
				continue;
			File destFile=new File(repoDir,fileName);
			try(InputStream input=part.getInputStream()){
				Files.copy(input, destFile.toPath(),StandardCopyOption.REPLACE_EXISTING);
				count++;
			}finally {
				part.delete();
			}
		}
		try(PrintWriter out=response.getWriter();){
			out.write(count+" files uploaded");
		}
	}

	@PATCH
	@Path("/upload")
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	@Produces(MediaType.TEXT_PLAIN)
	public void patch(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		//TODO
		try(PrintWriter out=response.getWriter();){
			out.write("file patched");
		}
	}

	@GET
	@Path("/download")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public void download(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String repoRelativePath=request.getParameter("path");
		if(repoRelativePath==null) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		File file=new File(repoDir,repoRelativePath);
		if(!file.isFile()) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("application/octet-stream");
		response.setContentLengthLong(file.length());
		response.setDateHeader("Last-Modified", file.lastModified());
		String filenameEncoded=new String(file.getName().getBytes(StandardCharsets.UTF_8),StandardCharsets.ISO_8859_1);
		response.setHeader("Content-Disposition", "attachment; filename=\""+filenameEncoded+"\"");
		try(ServletOutputStream output=response.getOutputStream()){
			Files.copy(file.toPath(), output);
		}
	}
}

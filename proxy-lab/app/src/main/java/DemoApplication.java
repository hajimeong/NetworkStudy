package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@SpringBootApplication
@RestController
public class DemoApplication {

	public static void main(String[] args){
		SpringApplication.run(DemoApplication.class, args);
	}

	@GetMapping("/echo")
	public Map<String, Object> echo(HttpServletRequest request){
		Map<String, Object> result=new LinkedHashMap<>();
		Map<String, String> headers=new LinkedHashMap<>();
		Enumeration<String> names=request.getHeaderNames();

		while(names.hasMoreElements()){
			String name=names.nextElement();
			headers.put(name, request.getHeader(name));
		}
		result.put("headers", headers);
		result.put("remoteAddr", request.getRemoteAddr());
		result.put("scheme", request.getScheme());

		return result;
	}
	
	@PostMapping("/upload")
	public Map<String, Object> upload(
		@RequestParam("file") org.springframework.web.multipart.MultipartFile file
	){
		return Map.of(
			"filename", file.getOriginalFilename(),
			"size", file.getSize()
		);
	
	}
}

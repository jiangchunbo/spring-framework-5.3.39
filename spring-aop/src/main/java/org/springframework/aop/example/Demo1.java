package org.springframework.aop.example;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;

public class Demo1 {


	public @interface RPC {
		String url() default "https://www.baidu.com";
	}

	// 假设定义了一个业务类 MyService
	public interface UserService {
		@RPC(url = "http://localhost:8080/user/info")
		String getUserInfo();
	}


	public static void main(String[] args) {

		// 在配置代理时：
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setTargetClass(UserService.class);  // 指定目标类
		proxyFactory.addAdvice(new MethodInterceptor() {
			@Nullable
			@Override
			public Object invoke(@Nonnull MethodInvocation invocation) throws Throwable {
				Method method = invocation.getMethod();
				return method;
			}
		});
	}
}

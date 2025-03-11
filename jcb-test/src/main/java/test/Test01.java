package test;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class Test01 {
	public static void main(String[] args) {
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext();
		ac.getBeanFactory().registerSingleton("jcb",new Jcb());
		ac.register(JcbConfig.class);
		ac.refresh();
	}
}

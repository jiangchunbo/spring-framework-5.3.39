package test;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({Jcb.class})
@ComponentScan("test")
public class JcbConfig {

	@Bean
	Jcb jcb() {
		return new Jcb();
	}
}

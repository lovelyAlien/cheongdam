package app.cheongdam.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 접수 상태·D-day는 한국 시간 자정에 바뀐다(P-01). 브라우저가 어제 응답을 재사용하지 않도록
 * 공고 API는 매번 서버에 확인하게 한다(API.md 1장).
 */
@Configuration
class ApiCacheConfig implements WebMvcConfigurer {

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
			@Override
			public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
					jakarta.servlet.http.HttpServletResponse response, Object handler) {
				response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
				return true;
			}
		}).addPathPatterns("/api/notices/**", "/api/notices");
	}
}

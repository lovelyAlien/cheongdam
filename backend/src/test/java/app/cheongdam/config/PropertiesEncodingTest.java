package app.cheongdam.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Spring Boot는 .properties를 ISO-8859-1로 읽어 한글 값이 깨진다(메일 발신자 이름이 깨졌던 일). 값은 ASCII만 쓴다. */
class PropertiesEncodingTest {

	@Test
	void 설정_파일_값에_ASCII가_아닌_글자가_없다() throws Exception {
		List<String> broken = new ArrayList<>();
		for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:application*.properties")) {
			for (var source : new PropertiesPropertySourceLoader().load(r.getFilename(), r)) {
				var props = (EnumerablePropertySource<?>) source;
				for (String name : props.getPropertyNames()) {
					if (!String.valueOf(props.getProperty(name)).chars().allMatch(c -> c < 0x80)) {
						broken.add(r.getFilename() + ": " + name);
					}
				}
			}
		}
		assertTrue(broken.isEmpty(), "한글 등은 Java 기본값이나 환경 변수로 넣어 주세요: " + broken);
	}
}

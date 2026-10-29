package app.cheongdam.config;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/** dev 프로파일이고 app.dev-seed=true일 때만 샘플 데이터를 넣는다(기본은 끔: 실제 수집 데이터만 본다). 날짜는 실행 시점 기준이라 매번 최신 상태가 된다. */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.dev-seed", havingValue = "true")
class DevSeedRunner implements ApplicationRunner {

	private final DataSource dataSource;

	DevSeedRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		try (var conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed/dev-seed.sql"));
		}
	}
}

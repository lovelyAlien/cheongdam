package app.cheongdam.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

/** 특별공급 유형. API 코드, notice_unit의 세대수 컬럼(엔티티 필드)과 짝을 이룬다. 기타(sp_etc)는 유형 칩·태그에 쓰지 않는다. */
public enum SpecialSupply {

	NEWLYWED("newlywed", "spNewlywed", NoticeUnit::getSpNewlywed),
	FIRST_HOME("first_home", "spFirstHome", NoticeUnit::getSpFirstHome),
	MULTI_CHILD("multi_child", "spMultiChild", NoticeUnit::getSpMultiChild),
	NEWBORN("newborn", "spNewborn", NoticeUnit::getSpNewborn),
	ELDERLY_PARENT("elderly_parent", "spElderly", NoticeUnit::getSpElderly),
	YOUTH("youth", "spYouth", NoticeUnit::getSpYouth),
	INSTITUTION("institution", "spInstitution", NoticeUnit::getSpInstitution);

	private final String code;
	private final String field;
	private final Function<NoticeUnit, Integer> households;

	SpecialSupply(String code, String field, Function<NoticeUnit, Integer> households) {
		this.code = code;
		this.field = field;
		this.households = households;
	}

	public String code() {
		return code;
	}

	/** NoticeUnit의 필드 이름. 조건 조립에 쓴다. */
	public String field() {
		return field;
	}

	/** 이 주택형의 해당 유형 세대수. 무순위·재공급은 null. */
	public Integer householdsOf(NoticeUnit unit) {
		return households.apply(unit);
	}

	public static Optional<SpecialSupply> ofCode(String code) {
		return Arrays.stream(values()).filter(s -> s.code.equals(code)).findFirst();
	}
}

package app.cheongdam.util;

/** 주소에서 시·군·구까지 잘라낸다(S-01 카드 위치). 규칙에 맞지 않으면 null. */
public final class District {

	private District() {
	}

	public static String of(String address) {
		if (address == null || address.isBlank()) {
			return null;
		}
		String[] tokens = address.strip().split("\\s+");
		StringBuilder out = new StringBuilder();
		int count = 0;
		for (int i = 1; i < tokens.length && count < 2; i++) {
			String t = tokens[i];
			if (t.endsWith("시") || t.endsWith("군") || t.endsWith("구")) {
				if (out.length() > 0) {
					out.append(' ');
				}
				out.append(t);
				count++;
			} else {
				break;
			}
		}
		return out.length() == 0 ? null : out.toString();
	}
}

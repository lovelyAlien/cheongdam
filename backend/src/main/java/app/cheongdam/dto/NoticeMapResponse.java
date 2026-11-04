package app.cheongdam.dto;

import app.cheongdam.dto.NoticeListResponse.Item;
import java.util.List;

/** GET /api/notices/map. items는 pins와 같은 순서이고 목록 카드와 같은 모양이다. */
public record NoticeMapResponse(int total, int unlocated, List<Pin> pins, List<Item> items) {

	/** accuracy: exact / district (P-21). 위치를 모르는 공고는 pins에 없다. */
	public record Pin(String houseManageNo, double lat, double lng, String accuracy) {
	}
}

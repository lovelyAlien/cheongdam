package app.cheongdam.repository;

import app.cheongdam.domain.ExtractedItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractedItemRepository extends JpaRepository<ExtractedItem, Long> {

	List<ExtractedItem> findByHouseManageNoAndVersionOrderByCategoryAscSortOrderAscIdAsc(String houseManageNo,
			int version);
}

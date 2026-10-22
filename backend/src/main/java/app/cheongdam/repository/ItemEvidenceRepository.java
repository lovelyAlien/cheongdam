package app.cheongdam.repository;

import app.cheongdam.domain.ItemEvidence;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemEvidenceRepository extends JpaRepository<ItemEvidence, Long> {

	@Query("select e from ItemEvidence e where e.item.id in :itemIds order by e.item.id, e.id")
	List<ItemEvidence> findByItemIds(@Param("itemIds") Collection<Long> itemIds);
}

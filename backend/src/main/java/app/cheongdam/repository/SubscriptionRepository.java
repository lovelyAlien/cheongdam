package app.cheongdam.repository;

import app.cheongdam.domain.Subscription;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

	Optional<Subscription> findByConfirmToken(String confirmToken);

	Optional<Subscription> findByManageToken(String manageToken);

	List<Subscription> findByEmailAndStatusInOrderById(String email, Collection<String> statuses);

	Optional<Subscription> findByEmailAndConditionsHashAndStatusIn(String email, String conditionsHash,
			Collection<String> statuses);
}

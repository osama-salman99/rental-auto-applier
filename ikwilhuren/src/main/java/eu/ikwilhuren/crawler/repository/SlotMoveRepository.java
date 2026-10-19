package eu.ikwilhuren.crawler.repository;

import eu.ikwilhuren.crawler.model.SlotMove;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlotMoveRepository extends JpaRepository<SlotMove, Long> {

    List<SlotMove> findAllByOrderByAtDesc(Limit limit);
}

package eu.ikwilhuren.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One move in the five-slot game: a request going in, or a request being given up.
 *
 * <p>The allocator trades requests around on its own — a home fills up, a better one appears, and
 * the weakest is swapped out. From the outside that is just five addresses that quietly change, so
 * this is the running commentary: what moved, when, and why.
 */
@Entity
@Table(name = "slot_moves")
public class SlotMove {

    public static final String APPLIED = "APPLIED";
    public static final String WITHDRAWN = "WITHDRAWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 16)
    private String action;

    @Column(length = 200)
    private String title;

    private Integer price;

    private Integer score;

    /** Plain English, e.g. "a better home needs the slot". */
    @Column(length = 300)
    private String reason;

    private Instant at;

    public SlotMove() {}

    public static SlotMove applied(String title, Integer price, Integer score, String reason) {
        return new SlotMove(APPLIED, title, price, score, reason);
    }

    public static SlotMove withdrawn(String title, Integer price, String reason) {
        return new SlotMove(WITHDRAWN, title, price, null, reason);
    }

    private SlotMove(String action, String title, Integer price, Integer score, String reason) {
        this.action = action;
        this.title = title;
        this.price = price;
        this.score = score;
        this.reason = reason;
        this.at = Instant.now();
    }

    public boolean isApplied() {
        return APPLIED.equals(action);
    }

    public Long getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getTitle() {
        return title;
    }

    public Integer getPrice() {
        return price;
    }

    public Integer getScore() {
        return score;
    }

    public String getReason() {
        return reason;
    }

    public Instant getAt() {
        return at;
    }
}

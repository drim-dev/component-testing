package dev.drim.relay.store;

import dev.drim.relay.store.entity.UserRow;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserRow, String> {
  Optional<UserRow> findByHandle(String handle);
}

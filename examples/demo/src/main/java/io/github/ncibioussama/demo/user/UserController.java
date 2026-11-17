package io.github.ncibioussama.demo.user;

import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
class UserController {

    record CreateUserRequest(String email, String name) {
    }

    private final UserService users;

    UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/{id}")
    ResponseEntity<User> byId(@PathVariable long id) {
        return ResponseEntity.of(users.findById(id));
    }

    @GetMapping(params = "email")
    User byEmail(@RequestParam String email) {
        return users.getByEmail(email);
    }

    @GetMapping("/email-taken")
    Map<String, Boolean> emailTaken(@RequestParam String email) {
        return Map.of("taken", users.isEmailTaken(email));
    }

    @PostMapping
    ResponseEntity<User> create(@RequestBody CreateUserRequest request) {
        User user = users.create(request.email(), request.name());
        return ResponseEntity.created(URI.create("/users/" + user.getId())).body(user);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable long id) {
        return users.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Demo only: how many lookups reached the database. Compare it before and after a request. */
    @GetMapping("/database-lookups")
    Map<String, Long> databaseLookups() {
        return Map.of("databaseLookups", users.databaseLookups());
    }
}

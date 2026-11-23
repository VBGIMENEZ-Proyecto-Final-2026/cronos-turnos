package ar.edu.um.cronos.adapters.out.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Fila de {@code app_user} con sus autoridades. Es un detalle de la persistencia: el resto usa {@code domain.User}. */
@Entity
@Table(name = "app_user")
class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String login;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String email;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "lang_key", nullable = false)
    private String langKey;

    @Column(nullable = false)
    private boolean activated;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_authority", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "authority", nullable = false)
    private Set<String> authorities = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserEntity() {
    }

    Long getId() { return id; }
    String getLogin() { return login; }
    String getPasswordHash() { return passwordHash; }
    String getFirstName() { return firstName; }
    String getLastName() { return lastName; }
    String getEmail() { return email; }
    String getImageUrl() { return imageUrl; }
    String getLangKey() { return langKey; }
    boolean isActivated() { return activated; }
    Set<String> getAuthorities() { return authorities; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }

    static UserEntity from(ar.edu.um.cronos.domain.User user) {
        UserEntity entity = new UserEntity();
        entity.id = user.id();
        entity.login = user.login();
        entity.passwordHash = user.passwordHash();
        entity.firstName = user.firstName();
        entity.lastName = user.lastName();
        entity.email = user.email();
        entity.imageUrl = user.imageUrl();
        entity.langKey = user.langKey();
        entity.activated = user.activated();
        entity.authorities = new HashSet<>(user.authorities());
        entity.createdAt = user.createdAt();
        entity.updatedAt = user.updatedAt();
        return entity;
    }

    ar.edu.um.cronos.domain.User toDomain() {
        return new ar.edu.um.cronos.domain.User(id, login, passwordHash, firstName, lastName, email, imageUrl,
                langKey, activated, authorities, createdAt, updatedAt);
    }
}

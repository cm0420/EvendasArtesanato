package com.TadeuArtesanatos.demo.common.config;

import com.TadeuArtesanatos.demo.catalog.entity.Category;
import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.repository.CategoryRepository;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import com.TadeuArtesanatos.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Popula o banco no startup, de forma idempotente (só cria o que faltar). Existe apenas
 * quando app.seed.enabled=true (ligado em prod), então não roda em dev nem nos testes.
 *
 * - Admin: criado a partir de ADMIN_EMAIL/ADMIN_PASSWORD. Sem senha definida, não cria
 *   um admin inseguro — apenas avisa (defina as variáveis na hospedagem).
 * - Catálogo de exemplo: categorias + peças com foto, só se o catálogo estiver vazio.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final PieceRepository pieceRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.admin-email:admin@jequi.com.br}")
    private String adminEmail;

    @Value("${app.seed.admin-password:}")
    private String adminPassword;

    private static final String IMG = "https://images.unsplash.com/photo-";
    private static final String IMG_PARAMS = "?fm=jpg&q=70&w=800&auto=format&fit=crop";

    @Override
    public void run(String... args) {
        seedAdmin();
        seedCatalog();
    }

    private void seedAdmin() {
        if (userRepository.existsByEmail(adminEmail)) {
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("Seed: ADMIN_PASSWORD não definido — admin '{}' não foi criado. "
                    + "Defina ADMIN_EMAIL/ADMIN_PASSWORD na hospedagem para criar o admin.", adminEmail);
            return;
        }
        userRepository.save(User.builder()
                .email(adminEmail)
                .password(passwordEncoder.encode(adminPassword))
                .role(Role.ADMIN)
                .build());
        log.info("Seed: admin '{}' criado.", adminEmail);
    }

    private void seedCatalog() {
        if (pieceRepository.count() > 0) {
            return;
        }
        Category vasos = category("Vasos", "Vasos e cachepôs de cerâmica pintados à mão.");
        Category esculturas = category("Esculturas", "Peças decorativas e figuras em barro.");
        Category utilitarios = category("Utilitários", "Tigelas, canecas e travessas para o dia a dia.");

        List<Piece> pieces = List.of(
                piece("Vaso Vale do Jequitinhonha", "Vaso de cerâmica torneado à mão, com engobe natural e pintura de grafismos do vale.",
                        "189.90", 3, img("1595351298020-038700609878"), Set.of(vasos)),
                piece("Cachepô Terracota", "Cachepô de barro queimado em forno a lenha, acabamento rústico.",
                        "129.00", 5, img("1597696929736-6d13bed8e6a8"), Set.of(vasos)),
                piece("Jarra Barro Cru", "Jarra utilitária de barro cru, vedada para uso com água e flores.",
                        "149.50", 2, img("1609881583302-61548332039c"), Set.of(vasos, utilitarios)),
                piece("Tigela Pintada à Mão", "Tigela de cerâmica esmaltada, pintura exclusiva peça a peça.",
                        "79.90", 8, img("1590605095243-072811dbe64c"), Set.of(utilitarios)),
                piece("Conjunto de Canecas", "Par de canecas de cerâmica artesanal, esmalte reativo.",
                        "98.00", 4, img("1607556671927-78a6605e290b"), Set.of(utilitarios)),
                piece("Travessa Rústica", "Travessa oval de barro, ideal para servir — resistente ao forno.",
                        "115.00", 3, img("1577576223085-3eb295cd414f"), Set.of(utilitarios)),
                piece("Escultura Figura do Vale", "Figura modelada à mão inspirada nas bonecas de barro do Jequitinhonha.",
                        "219.00", 1, img("1520408222757-6f9f95d87d5d"), Set.of(esculturas)),
                piece("Potiche Decorativo", "Potiche de cerâmica com tampa, pintura de grafismos em engobe.",
                        "169.90", 2, img("1597696929644-a2157a251a43"), Set.of(esculturas, vasos))
        );
        pieceRepository.saveAll(pieces);
        log.info("Seed: {} categorias e {} peças de exemplo criadas.", 3, pieces.size());
    }

    private Category category(String name, String description) {
        return categoryRepository.findByName(name)
                .orElseGet(() -> categoryRepository.save(
                        Category.builder().name(name).description(description).build()));
    }

    private Piece piece(String name, String description, String price, int stock, String imageUrl, Set<Category> categories) {
        return Piece.builder()
                .name(name)
                .description(description)
                .price(new BigDecimal(price))
                .stock(stock)
                .imageUrls(List.of(imageUrl))
                .categories(categories)
                .build();
    }

    private String img(String id) {
        return IMG + id + IMG_PARAMS;
    }
}

package com.TadeuArtesanatos.demo.common.config;

import com.TadeuArtesanatos.demo.catalog.entity.Category;
import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import com.TadeuArtesanatos.demo.catalog.repository.CategoryRepository;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.catalog.repository.PieceSetRepository;
import com.TadeuArtesanatos.demo.customer.entity.Address;
import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.order.entity.Order;
import com.TadeuArtesanatos.demo.order.entity.OrderItem;
import com.TadeuArtesanatos.demo.order.entity.OrderStatus;
import com.TadeuArtesanatos.demo.order.repository.OrderRepository;
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
import java.time.LocalDateTime;
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
    private final PieceSetRepository pieceSetRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
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
        seedOrders();
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
        Category decoracao = category("Decoração", "Peças decorativas para a casa — luminárias, pratos e mais.");

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
                        "169.90", 2, img("1597696929644-a2157a251a43"), Set.of(esculturas, vasos)),
                piece("Vaso Espiral Engobe", "Vaso alto com relevo em espiral e engobe branco sobre barro vermelho.",
                        "159.00", 4, img("1565193566173-7a0ee3dbe261"), Set.of(vasos)),
                piece("Cachepô Pequeno", "Cachepô pequeno de cerâmica para suculentas e temperos.",
                        "69.90", 10, img("1578749556568-bc2c40e68b61"), Set.of(vasos)),
                piece("Caneca Grafismo", "Caneca de cerâmica com grafismos pintados à mão, 300 ml.",
                        "54.90", 12, img("1493106641515-6b5631de4bb9"), Set.of(utilitarios)),
                piece("Bowl de Cerâmica", "Bowl fundo esmaltado, ideal para sopas, caldos e açaí.",
                        "72.00", 6, img("1610701596007-11502861dcfa"), Set.of(utilitarios)),
                piece("Prato Decorativo Pintado", "Prato de parede com pintura central inspirada na flora do vale.",
                        "89.90", 5, img("1584589167171-541ce45f1eea"), Set.of(decoracao)),
                piece("Luminária de Barro", "Luminária de mesa em barro vazado, projeta grafismos na parede.",
                        "239.00", 2, img("1606760227091-3dd870d97f1d"), Set.of(decoracao)),
                piece("Figura Pássaro do Vale", "Pássaro modelado à mão, peça de coleção da tradição ceramista.",
                        "139.00", 3, img("1578500494198-246f612d3b3d"), Set.of(esculturas)),
                piece("Porta-incenso Artesanal", "Porta-incenso de cerâmica esmaltada, com canaleta para o varetão.",
                        "44.90", 15, img("1556760544-74068565f05c"), Set.of(decoracao))
        );
        pieceRepository.saveAll(pieces);

        List<PieceSet> sets = List.of(
                pieceSet("Conjunto Café da Manhã",
                        "Caneca, bowl e tigela combinando — o trio da mesa do café, com desconto.",
                        "169.00", Set.of(pieces.get(10), pieces.get(11), pieces.get(3))),
                pieceSet("Conjunto Vale Decoração",
                        "Vaso, potiche e prato decorativo para compor um canto da casa com a cara do vale.",
                        "399.00", Set.of(pieces.get(0), pieces.get(7), pieces.get(12))),
                pieceSet("Conjunto Mesa Posta",
                        "Travessa, tigela e jarra de barro para servir — mesa posta artesanal.",
                        "299.00", Set.of(pieces.get(5), pieces.get(3), pieces.get(2)))
        );
        pieceSetRepository.saveAll(sets);

        log.info("Seed: {} categorias, {} peças e {} conjuntos de exemplo criados.",
                4, pieces.size(), sets.size());
    }

    private PieceSet pieceSet(String name, String description, String price, Set<Piece> pieces) {
        return PieceSet.builder()
                .name(name)
                .description(description)
                .price(new BigDecimal(price))
                .pieces(pieces)
                .build();
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

    /**
     * Pedidos de exemplo para o painel admin não nascer vazio: clientes, vendas espalhadas nos
     * últimos ~30 dias e status variados (os gráficos usam paid_at/total_amount; os filtros usam
     * o status; "mais vendidas" conta itens de pedidos PAGO/ENVIADO/ENTREGUE). Só roda se não
     * houver nenhum pedido ainda — depois do primeiro boot, convive com os pedidos reais.
     */
    private void seedOrders() {
        if (orderRepository.count() > 0) {
            return;
        }
        List<Piece> pieces = pieceRepository.findAll();
        if (pieces.isEmpty()) {
            return;
        }

        Customer ana = customer("Ana", "Ribeiro", "ana.cliente@exemplo.com", "11144477735",
                "+5533998810011", "Rua das Flores", "120", "Centro", "Araçuaí", "MG", "39600000");
        Customer bruno = customer("Bruno", "Teixeira", "bruno.cliente@exemplo.com", "52998224725",
                "+5531997720022", "Av. Jequitinhonha", "45", "Vale Verde", "Diamantina", "MG", "39100000");
        Customer carla = customer("Carla", "Mendes", "carla.cliente@exemplo.com", "39053344705",
                "+5533998630033", "Travessa do Barro", "8", "Alto", "Minas Novas", "MG", "39650000");

        order(ana,   OrderStatus.ENTREGUE, 28, "BR123456785BR", items(item(pick(pieces, 0), 1), item(pick(pieces, 3), 2)));
        order(bruno, OrderStatus.ENTREGUE, 25, "BR123456799BR", items(item(pick(pieces, 4), 1)));
        order(carla, OrderStatus.ENVIADO,  20, "BR223456785BR", items(item(pick(pieces, 1), 2)));
        order(ana,   OrderStatus.ENVIADO,  16, "BR223456799BR", items(item(pick(pieces, 6), 1), item(pick(pieces, 3), 1)));
        order(bruno, OrderStatus.PAGO,     12, null,            items(item(pick(pieces, 2), 1)));
        order(carla, OrderStatus.PAGO,      9, null,            items(item(pick(pieces, 5), 2), item(pick(pieces, 3), 1)));
        order(ana,   OrderStatus.PAGO,      6, null,            items(item(pick(pieces, 7), 1)));
        order(bruno, OrderStatus.ENTREGUE,  5, "BR323456785BR", items(item(pick(pieces, 0), 1)));
        order(carla, OrderStatus.ESTORNO_SOLICITADO, 4, "BR323456799BR", items(item(pick(pieces, 4), 1)));
        order(ana,   OrderStatus.CANCELADO, 3, null,            items(item(pick(pieces, 1), 1)));
        order(bruno, OrderStatus.PAGO,      1, null,            items(item(pick(pieces, 3), 3)));

        // Um pedido comprando um conjunto/kit (item de PieceSet, não de peça avulsa).
        List<PieceSet> sets = pieceSetRepository.findAll();
        if (!sets.isEmpty()) {
            order(carla, OrderStatus.ENTREGUE, 10, "BR423456785BR", items(kitItem(sets.get(0), 1)));
        }

        log.info("Seed: 3 clientes e pedidos de exemplo criados.");
    }

    private Piece pick(List<Piece> pieces, int index) {
        return pieces.get(index % pieces.size());
    }

    private Customer customer(String first, String last, String email, String cpf, String phone,
                              String street, String number, String neighborhood, String city,
                              String state, String zip) {
        User user = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode("cliente123"))
                .role(Role.CLIENTE)
                .build());
        Customer c = Customer.builder()
                .user(user)
                .firstName(first)
                .lastName(last)
                .email(email)
                .phone(phone)
                .cpf(cpf)
                .build();
        c.addAddress(Address.builder()
                .label("Casa")
                .recipientName(first + " " + last)
                .recipientPhone(phone)
                .zipCode(zip)
                .street(street)
                .number(number)
                .neighborhood(neighborhood)
                .city(city)
                .state(state)
                .country("BR")
                .isDefault(true)
                .build());
        return customerRepository.save(c);
    }

    private OrderItem item(Piece piece, int quantity) {
        BigDecimal unit = piece.getPrice();
        return OrderItem.builder()
                .piece(piece)
                .itemName(piece.getName())
                .unitPrice(unit)
                .quantity(quantity)
                .subtotal(unit.multiply(BigDecimal.valueOf(quantity)))
                .build();
    }

    private OrderItem kitItem(PieceSet set, int quantity) {
        BigDecimal unit = set.getPrice();
        return OrderItem.builder()
                .pieceSet(set)
                .itemName(set.getName())
                .unitPrice(unit)
                .quantity(quantity)
                .subtotal(unit.multiply(BigDecimal.valueOf(quantity)))
                .build();
    }

    private List<OrderItem> items(OrderItem... its) {
        return List.of(its);
    }

    private void order(Customer customer, OrderStatus status, int daysAgo, String trackingCode,
                       List<OrderItem> orderItems) {
        LocalDateTime when = LocalDateTime.now().minusDays(daysAgo);
        Address addr = customer.getAddresses().get(0);

        Order order = Order.builder()
                .customer(customer)
                .status(status)
                .totalAmount(BigDecimal.ZERO)
                .recipientName(addr.getRecipientName())
                .recipientPhone(addr.getRecipientPhone())
                .shippingZipCode(addr.getZipCode())
                .shippingStreet(addr.getStreet())
                .shippingNumber(addr.getNumber())
                .shippingNeighborhood(addr.getNeighborhood())
                .shippingCity(addr.getCity())
                .shippingState(addr.getState())
                .checkoutStartedAt(when)
                .build();

        orderItems.forEach(order::addItem);
        order.applyShipping("PAC", "PAC - Correios", new BigDecimal("24.90"), 7);

        // paid_at só nos pedidos de fato pagos — é a data que alimenta os gráficos de receita.
        if (status == OrderStatus.PAGO || status == OrderStatus.ENVIADO
                || status == OrderStatus.ENTREGUE || status == OrderStatus.ESTORNO_SOLICITADO) {
            order.setPaidAt(when);
        }
        if (trackingCode != null) {
            order.setTrackingCode(trackingCode);
        }
        if (status == OrderStatus.ESTORNO_SOLICITADO) {
            order.setStatusBeforeRefund(OrderStatus.ENVIADO);
            order.setRefundReason("Peça chegou com uma trinca.");
            order.setRefundRequestedAt(when.plusDays(1));
        }
        orderRepository.save(order);
    }
}

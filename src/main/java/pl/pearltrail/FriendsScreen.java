package pl.pearltrail;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Lista znajomych - ich perły nie mają smugi. */
public class FriendsScreen extends Screen {

    private static final int PER_PAGE = 7;

    private final Screen parent;
    private TextFieldWidget nameField;
    private int page = 0;
    private Text status = Text.empty();
    private int statusColor = 0xAAAAAA;

    public FriendsScreen(Screen parent) {
        super(Text.literal("Pearl Trail – znajomi"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TrailConfig cfg = TrailConfig.INSTANCE;
        List<String> friends = cfg.friends;
        int pages = Math.max(1, (friends.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);

        int w = 240;
        int x = this.width / 2 - w / 2;
        int y = 40;

        String typed = nameField != null ? nameField.getText() : "";
        nameField = new TextFieldWidget(this.textRenderer, x, y, w - 84, 20, Text.literal("Nick"));
        nameField.setMaxLength(16);
        nameField.setPlaceholder(Text.literal("Wpisz nick gracza..."));
        nameField.setText(typed);
        addDrawableChild(nameField);
        setInitialFocus(nameField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Dodaj"), b -> addTyped())
                .dimensions(x + w - 80, y, 80, 20).build());

        y += 36;
        int start = page * PER_PAGE;
        for (int i = start; i < Math.min(friends.size(), start + PER_PAGE); i++) {
            String name = friends.get(i);
            addDrawableChild(ButtonWidget.builder(Text.literal("Usuń"), b -> {
                        cfg.removeFriend(name);
                        setStatus("Usunięto " + name, 0xFFAA55);
                        clearAndInit();
                    })
                    .dimensions(x + w - 60, y, 60, 20).build());
            y += 22;
        }

        int bottom = 76 + PER_PAGE * 22 + 8;
        if (pages > 1) {
            ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { page--; clearAndInit(); })
                    .dimensions(x, bottom, 20, 20).build();
            ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { page++; clearAndInit(); })
                    .dimensions(x + w - 20, bottom, 20, 20).build();
            prev.active = page > 0;
            next.active = page < pages - 1;
            addDrawableChild(prev);
            addDrawableChild(next);
        }

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
                .dimensions(this.width / 2 - 100, bottom + 28, 200, 20).build());
    }

    private void addTyped() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) return;
        if (TrailConfig.INSTANCE.isFriend(name)) {
            setStatus(name + " już jest na liście", 0xFFAA55);
        } else if (TrailConfig.INSTANCE.addFriend(name)) {
            setStatus("Dodano " + name, 0x55FF55);
            nameField.setText("");
        } else {
            setStatus("Niepoprawny nick (litery, cyfry, _ , max 16 znaków)", 0xFF5555);
        }
        clearAndInit();
    }

    private void setStatus(String text, int color) {
        status = Text.literal(text);
        statusColor = color;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (nameField != null && nameField.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            addTyped();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xB04CFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Perły znajomych nie mają smugi ani podświetlenia"), this.width / 2, 27, 0xAAAAAA);

        int w = 240;
        int x = this.width / 2 - w / 2;
        context.drawTextWithShadow(this.textRenderer, status, x, 64, statusColor);

        List<String> friends = TrailConfig.INSTANCE.friends;
        int y = 76;
        if (friends.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("Brak znajomych na liście"), x, y + 6, 0x777777);
        }
        int start = page * PER_PAGE;
        for (int i = start; i < Math.min(friends.size(), start + PER_PAGE); i++) {
            String name = friends.get(i);
            boolean online = isOnline(name);
            context.fill(x, y, x + w - 64, y + 20, 0x66000000);
            context.drawTextWithShadow(this.textRenderer, Text.literal(name), x + 6, y + 6, 0xFFFFFF);
            if (online) {
                context.drawTextWithShadow(this.textRenderer, Text.literal("● online"),
                        x + w - 64 - 52, y + 6, 0x55FF55);
            }
            y += 22;
        }

        int pages = Math.max(1, (friends.size() + PER_PAGE - 1) / PER_PAGE);
        if (pages > 1) {
            int bottom = 76 + PER_PAGE * 22 + 8;
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Strona " + (page + 1) + "/" + pages), this.width / 2, bottom + 6, 0xFFFFFF);
        }
    }

    private static boolean isOnline(String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) return false;
        for (PlayerListEntry e : client.getNetworkHandler().getPlayerList()) {
            if (e.getProfile().getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    @Override
    public void close() {
        TrailConfig.save();
        if (this.client != null) this.client.setScreen(parent);
    }
}

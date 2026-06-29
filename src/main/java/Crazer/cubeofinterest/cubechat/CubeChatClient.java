package Crazer.cubeofinterest.cubechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CubeChat.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public class CubeChatClient {
    public static final int CLIENT_CHAT_LINE_LIMIT = 5000;

    public static int getClientChatLineLimit() {
        return CLIENT_CHAT_LINE_LIMIT;
    }

    private static final int PANEL_PADDING_X = 6;
    private static final int PANEL_PADDING_Y = 4;
    private static final int BUTTON_GAP = 6;
    private static final int CHAT_LEFT = 4;

    private static final ButtonArea allButton = new ButtonArea();
    private static final ButtonArea localButton = new ButtonArea();
    private static final ButtonArea globalButton = new ButtonArea();
    private static final ButtonArea privateButton = new ButtonArea();

    @SubscribeEvent
    public static void onClientChatReceived(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        if (message == null) {
            return;
        }

        String text = message.getString();
        if (text == null || text.isBlank()) {
            return;
        }

        String lower = text.toLowerCase(java.util.Locale.ROOT);

        if (CubeChat.shouldHideJoinLeaveMessages() && isVanillaJoinMessage(lower)) {
            event.setCanceled(true);
        }
    }

    private static boolean isVanillaJoinMessage(String lower) {
        return lower.contains("присоединился к игре")
                || lower.contains("присоединилась к игре")
                || lower.contains("joined the game")
                || lower.contains("покинул игру")
                || lower.contains("покинула игру")
                || lower.contains("left the game");
    }

    @SubscribeEvent
    public static void onRenderChatScreen(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);

        String allText = "[ALL]";
        String localText = "[L]";
        String globalText = "[G]";
        String privateText = "[PM]";

        int buttonsWidth =
                mc.font.width(allText)
                        + BUTTON_GAP
                        + mc.font.width(localText)
                        + BUTTON_GAP
                        + mc.font.width(globalText)
                        + BUTTON_GAP
                        + mc.font.width(privateText);

        int panelWidth = buttonsWidth + PANEL_PADDING_X * 2;
        int panelHeight = mc.font.lineHeight + PANEL_PADDING_Y * 2;

        int chatHeight = mc.gui.getChat().getHeight();

        int panelX = CHAT_LEFT;
        int panelY = event.getScreen().height - 40 - chatHeight - 16;

        int x = panelX + PANEL_PADDING_X;
        int y = panelY + PANEL_PADDING_Y;

        graphics.fill(
                panelX,
                panelY,
                panelX + panelWidth,
                panelY + panelHeight,
                0xDD000000
        );

        x = drawButton(graphics, mc, x, y, allText, 0xFFFFFFFF, allButton);
        x += BUTTON_GAP;

        x = drawButton(graphics, mc, x, y, localText, 0xFF55FF55, localButton);
        x += BUTTON_GAP;

        x = drawButton(graphics, mc, x, y, globalText, 0xFFFFAA00, globalButton);
        x += BUTTON_GAP;

        drawButton(graphics, mc, x, y, privateText, 0xFFFF55FF, privateButton);

        graphics.pose().popPose();
    }

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.player.connection == null) {
            return;
        }

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();

        if (allButton.contains(mouseX, mouseY)) {
            mc.player.connection.sendCommand("chat all");
            event.setCanceled(true);
            return;
        }

        if (localButton.contains(mouseX, mouseY)) {
            mc.player.connection.sendCommand("chat local");
            event.setCanceled(true);
            return;
        }

        if (globalButton.contains(mouseX, mouseY)) {
            mc.player.connection.sendCommand("chat global");
            event.setCanceled(true);
            return;
        }

        if (privateButton.contains(mouseX, mouseY)) {
            mc.player.connection.sendCommand("chat pm");
            event.setCanceled(true);
        }
    }

    public static void clearChatMessages() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.gui == null) {
            return;
        }

        mc.gui.getChat().clearMessages(false);
    }

    private static int drawButton(
            GuiGraphics graphics,
            Minecraft mc,
            int x,
            int y,
            String text,
            int color,
            ButtonArea area
    ) {
        int width = mc.font.width(text);

        graphics.drawString(mc.font, Component.literal(text), x, y, color, true);

        area.x1 = x;
        area.y1 = y;
        area.x2 = x + width;
        area.y2 = y + mc.font.lineHeight;

        return x + width;
    }

    private static class ButtonArea {
        int x1;
        int y1;
        int x2;
        int y2;

        boolean contains(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }
}
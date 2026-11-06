package nova.nova_client.client;

public class Utils {
    public static boolean isHovering(int mouseX, int mouseY, int x0, int y0, int x1, int y1){
        return mouseX >= x0 &&
                mouseX <= x1 &&
                mouseY >= y0 &&
                mouseY <= y1;
    }
}

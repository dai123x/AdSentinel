namespace AdSentinel;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(initiallyOwned: true, "AdSentinel_SingleInstance", out bool isNew);
        if (!isNew)
        {
            MessageBox.Show("AdSentinel 已在运行,请查看系统托盘图标。",
                "AdSentinel", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new TrayContext());
        GC.KeepAlive(mutex);
    }
}

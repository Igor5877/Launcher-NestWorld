using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace Prestarter.Controls
{
    public class CustomProgressBar : ProgressBar
    {
        private const float MarqueeStripeWidth = 0.34f;

        private Color _customColor = Color.Green;
        private Color? _trackColor;
        private int _radius = 6;
        private float _marqueeOffset = -MarqueeStripeWidth;
        private readonly Timer _marqueeTimer;

        public Color ProgressBarColor
        {
            get => _customColor;
            set { _customColor = value; Invalidate(); }
        }

        /// <summary>
        ///     Цвет дорожки прогресс-бара. Если не задан явно, используется BackColor (старое поведение).
        /// </summary>
        public Color TrackColor
        {
            get => _trackColor ?? BackColor;
            set { _trackColor = value; Invalidate(); }
        }

        public int BorderRadius
        {
            get => _radius;
            set { _radius = value; Invalidate(); }
        }

        /// <summary>
        ///     Длительность одного прохода marquee-полосы слева направо, мс.
        /// </summary>
        public int MarqueeDurationMs { get; set; } = 1400;

        public new ProgressBarStyle Style
        {
            get => base.Style;
            set
            {
                base.Style = value;
                if (value == ProgressBarStyle.Marquee)
                    _marqueeTimer.Start();
                else
                    _marqueeTimer.Stop();
            }
        }

        public CustomProgressBar()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);

            _marqueeTimer = new Timer { Interval = 16 };
            _marqueeTimer.Tick += MarqueeTimerOnTick;
        }

        private void MarqueeTimerOnTick(object sender, EventArgs e)
        {
            float travel = 1f + MarqueeStripeWidth;
            _marqueeOffset += travel * _marqueeTimer.Interval / Math.Max(1, MarqueeDurationMs);
            if (_marqueeOffset > 1f)
                _marqueeOffset = -MarqueeStripeWidth;

            Invalidate();
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
                _marqueeTimer.Dispose();

            base.Dispose(disposing);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var rect = new Rectangle(0, 0, ClientSize.Width, ClientSize.Height);

            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;

            using (GraphicsPath path = CreateRoundedRectangle(rect, BorderRadius))
            {
                var oldClip = e.Graphics.Clip;
                e.Graphics.SetClip(path, CombineMode.Replace);

                using (SolidBrush trackBrush = new SolidBrush(TrackColor))
                {
                    e.Graphics.FillPath(trackBrush, path);
                }

                if (base.Style == ProgressBarStyle.Marquee)
                {
                    DrawMarquee(e.Graphics, rect);
                }
                else if (Value > 0)
                {
                    float percent = (float)Value / Maximum;
                    int width = (int)(rect.Width * percent);

                    Rectangle progressRect = new Rectangle(0, 0, width, rect.Height);
                    using (GraphicsPath progressPath = CreateRoundedRectangle(progressRect, BorderRadius))
                    using (SolidBrush brush = new SolidBrush(ProgressBarColor))
                    {
                        e.Graphics.FillPath(brush, progressPath);
                    }
                }

                e.Graphics.Clip = oldClip;
            }
        }

        private void DrawMarquee(Graphics g, Rectangle rect)
        {
            float stripeWidth = rect.Width * MarqueeStripeWidth;
            float x = rect.Width * _marqueeOffset;
            var stripeRect = new RectangleF(x, 0, stripeWidth, rect.Height);

            using (var brush = new LinearGradientBrush(stripeRect, ProgressBarColor, ProgressBarColor, LinearGradientMode.Horizontal))
            {
                var transparent = Color.FromArgb(0, ProgressBarColor);
                brush.InterpolationColors = new ColorBlend(4)
                {
                    Colors = new[] { transparent, ProgressBarColor, ProgressBarColor, transparent },
                    Positions = new[] { 0f, 0.25f, 0.75f, 1f }
                };

                g.FillRectangle(brush, stripeRect);
            }
        }

        // Вспомогательный метод для создания пути с закругленными углами
        private GraphicsPath CreateRoundedRectangle(Rectangle rect, int radius)
        {
            GraphicsPath path = new GraphicsPath();
            int diameter = radius * 2;

            // Левый верхний угол
            path.AddArc(rect.X, rect.Y, diameter, diameter, 180, 90);

            // Верхний край
            path.AddLine(rect.X + radius, rect.Y, rect.Right - radius, rect.Y);

            // Правый верхний угол
            path.AddArc(rect.Right - diameter, rect.Y, diameter, diameter, 270, 90);

            // Правый край
            path.AddLine(rect.Right, rect.Y + radius, rect.Right, rect.Bottom - radius);

            // Правый нижний угол
            path.AddArc(rect.Right - diameter, rect.Bottom - diameter, diameter, diameter, 0, 90);

            // Нижний край
            path.AddLine(rect.Right - radius, rect.Bottom, rect.X + radius, rect.Bottom);

            // Левый нижний угол
            path.AddArc(rect.X, rect.Bottom - diameter, diameter, diameter, 90, 90);

            // Левый край
            path.AddLine(rect.X, rect.Bottom - radius, rect.X, rect.Y + radius);

            path.CloseFigure();
            return path;
        }
    }
}

using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace Prestarter.Controls
{
    public class CircularButton : Button
    {
        private Color _hoverColor = Color.FromArgb(232, 17, 35);
        private Color _normalColor = Color.FromArgb(56, 255, 255, 255);
        private Color _iconColor = Color.White;
        private bool _isHovered = false;

        /// <summary>
        ///     Цвет заливки круга при наведении курсора
        /// </summary>
        public Color HoverColor
        {
            get => _hoverColor;
            set { _hoverColor = value; Invalidate(); }
        }

        /// <summary>
        ///     Цвет рамки круга в обычном состоянии (может быть полупрозрачным)
        /// </summary>
        public Color NormalColor
        {
            get => _normalColor;
            set { _normalColor = value; Invalidate(); }
        }

        /// <summary>
        ///     Цвет крестика внутри кнопки
        /// </summary>
        public Color IconColor
        {
            get => _iconColor;
            set { _iconColor = value; Invalidate(); }
        }

        public CircularButton()
        {
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            Size = new Size(23, 23);
            BackColor = Color.Transparent;
            Cursor = Cursors.Hand;

            // Включаем двойную буферизацию
            SetStyle(
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer |
                ControlStyles.ResizeRedraw |
                ControlStyles.SupportsTransparentBackColor |
                ControlStyles.UserPaint,
                true);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;

            var bounds = new Rectangle(0, 0, Width - 1, Height - 1);

            using (var path = new GraphicsPath())
            {
                path.AddEllipse(bounds);
                this.Region = new Region(path);

                if (_isHovered)
                {
                    using (SolidBrush brush = new SolidBrush(_hoverColor))
                    {
                        e.Graphics.FillPath(brush, path);
                    }
                }
                else if (_normalColor.A > 0)
                {
                    using (Pen pen = new Pen(_normalColor, 1f))
                    {
                        e.Graphics.DrawPath(pen, path);
                    }
                }

                DrawCross(e.Graphics);
            }
        }

        private void DrawCross(Graphics g)
        {
            float minSide = Math.Min(Width, Height);
            float half = minSide * 0.39f / 2f;
            float cx = Width / 2f;
            float cy = Height / 2f;
            float strokeWidth = Math.Max(1f, minSide * 1.4f / 23f);

            using (Pen pen = new Pen(_iconColor, strokeWidth) { StartCap = LineCap.Round, EndCap = LineCap.Round })
            {
                g.DrawLine(pen, cx - half, cy - half, cx + half, cy + half);
                g.DrawLine(pen, cx + half, cy - half, cx - half, cy + half);
            }
        }

        protected override void OnMouseEnter(EventArgs e)
        {
            _isHovered = true;
            Invalidate();
            base.OnMouseEnter(e);
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            _isHovered = false;
            Invalidate();
            base.OnMouseLeave(e);
        }
    }
}

using Prestarter.Controls;

namespace Prestarter
{
    partial class PrestarterForm
    {
        /// <summary>
        /// Обязательная переменная конструктора.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Освободить все используемые ресурсы.
        /// </summary>
        /// <param name="disposing">истинно, если управляемый ресурс должен быть удален; иначе ложно.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Код, автоматически созданный конструктором форм Windows

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            System.ComponentModel.ComponentResourceManager resources = new System.ComponentModel.ComponentResourceManager(typeof(PrestarterForm));
            this.mainProgressBar = new Prestarter.Controls.CustomProgressBar();
            this.logoLabel = new System.Windows.Forms.Label();
            this.statusLabel = new System.Windows.Forms.Label();
            this.logoPictureBox = new System.Windows.Forms.PictureBox();
            this.exitButton = new Prestarter.Controls.CircularButton();
            ((System.ComponentModel.ISupportInitialize)(this.logoPictureBox)).BeginInit();
            this.SuspendLayout();
            // 
            // mainProgressBar
            // 
            this.mainProgressBar.BackColor = System.Drawing.SystemColors.ActiveCaption;
            this.mainProgressBar.BorderRadius = 6;
            this.mainProgressBar.ForeColor = System.Drawing.SystemColors.InactiveBorder;
            this.mainProgressBar.Location = new System.Drawing.Point(11, 97);
            this.mainProgressBar.Margin = new System.Windows.Forms.Padding(2);
            this.mainProgressBar.Name = "mainProgressBar";
            this.mainProgressBar.ProgressBarColor = System.Drawing.Color.Green;
            this.mainProgressBar.Size = new System.Drawing.Size(385, 14);
            this.mainProgressBar.Style = System.Windows.Forms.ProgressBarStyle.Continuous;
            this.mainProgressBar.TabIndex = 0;
            //
            // logoLabel
            //
            this.logoLabel.AutoSize = true;
            this.logoLabel.Font = new System.Drawing.Font("Segoe UI", 23.25F, System.Drawing.FontStyle.Bold, System.Drawing.GraphicsUnit.Point, ((byte)(204)));
            this.logoLabel.Location = new System.Drawing.Point(101, 12);
            this.logoLabel.Margin = new System.Windows.Forms.Padding(2, 0, 2, 0);
            this.logoLabel.Name = "logoLabel";
            this.logoLabel.Size = new System.Drawing.Size(236, 37);
            this.logoLabel.TabIndex = 1;
            this.logoLabel.Text = "GravitLauncher";
            //
            // statusLabel
            //
            this.statusLabel.AutoSize = true;
            this.statusLabel.Font = new System.Drawing.Font("Segoe UI", 9F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(204)));
            this.statusLabel.Location = new System.Drawing.Point(102, 52);
            this.statusLabel.Margin = new System.Windows.Forms.Padding(2, 0, 2, 0);
            this.statusLabel.Name = "statusLabel";
            this.statusLabel.Size = new System.Drawing.Size(90, 13);
            this.statusLabel.TabIndex = 2;
            this.statusLabel.Text = "Статус загрузки";
            // 
            // logoPictureBox
            // 
            this.logoPictureBox.BackgroundImage = ((System.Drawing.Image)(resources.GetObject("logoPictureBox.BackgroundImage")));
            this.logoPictureBox.BackgroundImageLayout = System.Windows.Forms.ImageLayout.Zoom;
            this.logoPictureBox.Location = new System.Drawing.Point(11, 11);
            this.logoPictureBox.Margin = new System.Windows.Forms.Padding(2);
            this.logoPictureBox.Name = "logoPictureBox";
            this.logoPictureBox.Size = new System.Drawing.Size(78, 77);
            this.logoPictureBox.TabIndex = 3;
            this.logoPictureBox.TabStop = false;
            this.logoPictureBox.UseWaitCursor = true;
            //
            // exitButton
            //
            this.exitButton.BackColor = System.Drawing.Color.Transparent;
            this.exitButton.Cursor = System.Windows.Forms.Cursors.Hand;
            this.exitButton.FlatStyle = System.Windows.Forms.FlatStyle.Flat;
            this.exitButton.Location = new System.Drawing.Point(375, 9);
            this.exitButton.Margin = new System.Windows.Forms.Padding(2);
            this.exitButton.Name = "exitButton";
            this.exitButton.Size = new System.Drawing.Size(23, 23);
            this.exitButton.TabIndex = 4;
            this.exitButton.UseVisualStyleBackColor = false;
            this.exitButton.Click += new System.EventHandler(this.CloseWindow);
            // 
            // PrestarterForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(6F, 13F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.BackColor = System.Drawing.Color.FromArgb(((int)(((byte)(17)))), ((int)(((byte)(17)))), ((int)(((byte)(20)))));
            this.ClientSize = new System.Drawing.Size(407, 121);
            this.Controls.Add(this.exitButton);
            this.Controls.Add(this.logoPictureBox);
            this.Controls.Add(this.statusLabel);
            this.Controls.Add(this.logoLabel);
            this.Controls.Add(this.mainProgressBar);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.None;
            this.Icon = global::Prestarter.Properties.Resources.AppIcon;
            this.Margin = new System.Windows.Forms.Padding(2);
            this.Name = "PrestarterForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterScreen;
            this.Text = "GravitLauncher Prestarter";
            this.Load += new System.EventHandler(this.FormLoaded);
            this.MouseDown += new System.Windows.Forms.MouseEventHandler(this.FormMouseDown);
            this.MouseMove += new System.Windows.Forms.MouseEventHandler(this.FormMouseMove);
            this.MouseUp += new System.Windows.Forms.MouseEventHandler(this.FormMouseUp);
            ((System.ComponentModel.ISupportInitialize)(this.logoPictureBox)).EndInit();
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion
        
        protected CustomProgressBar mainProgressBar;
        private System.Windows.Forms.Label logoLabel;
        private System.Windows.Forms.Label statusLabel;
        private System.Windows.Forms.PictureBox logoPictureBox;
        private CircularButton exitButton;
    }
}

